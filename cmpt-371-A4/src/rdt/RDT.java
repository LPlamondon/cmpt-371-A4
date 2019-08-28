
/**
 * @author Lukas Plamondon		lplamond		301 263 735
 *
 */

package rdt;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class RDT {

	public static int MSS = 100; // Max segement size in bytes
	public static int RTO = 500; // Retransmission Timeout in msec
	public static final int ERROR = -1;
	public static final int MAX_BUF_SIZE = 50;  

	
	public static double lossRate = 0.0;
	public static Random random = new Random(); 
	public static Timer timer = new Timer();	
	
	private DatagramSocket socket; 
	private InetAddress dst_ip;
	private int dst_port;
	private int local_port; 
	
	private RDTBuffer sndBuf;
	private RDTBuffer rcvBuf;
	
	private ReceiverThread rcvThread;  
	
	public int seqNumber = 0;
	public int ackNumber = 0;
	
	
	RDT (String dst_hostname_, int dst_port_, int local_port_) 
	{
		local_port = local_port_;
		dst_port = dst_port_; 
		try {
			 socket = new DatagramSocket(local_port);
			 dst_ip = InetAddress.getByName(dst_hostname_);
		 } catch (IOException e) {
			 System.out.println("RDT constructor: " + e);
		 }
		sndBuf = new RDTBuffer(MAX_BUF_SIZE);
			rcvBuf = new RDTBuffer(1);
	
		rcvThread = new ReceiverThread(rcvBuf, sndBuf, socket, dst_ip, dst_port, this);
		rcvThread.start();
	}
	
	RDT (String dst_hostname_, int dst_port_, int local_port_, int sndBufSize, int rcvBufSize)
	{
		local_port = local_port_;
		dst_port = dst_port_;
		 try {
			 socket = new DatagramSocket(local_port);
			 dst_ip = InetAddress.getByName(dst_hostname_);
		 } catch (IOException e) {
			 System.out.println("RDT constructor: " + e);
		 }
		sndBuf = new RDTBuffer(sndBufSize);
		rcvBuf = new RDTBuffer(1);

		
		rcvThread = new ReceiverThread(rcvBuf, sndBuf, socket, dst_ip, dst_port, this);
		rcvThread.start();
	}
	
	public static void setLossRate(double rate) {lossRate = rate;}

	// called by app
	// returns total number of sent bytes  
	public int send(byte[] data, int size) {
		
		//****** complete
		
		// divide data into segments
		boolean lastSegment = false;
		int dataPos = 0;
		ArrayList <RDTSegment> segmentList = new ArrayList <RDTSegment>();
		
		while(lastSegment == false) {
			RDTSegment segment = new RDTSegment();
			int length = 0;
			
			if(dataPos + MSS < size) {
				segment.data = Arrays.copyOfRange(data, dataPos, dataPos + MSS);
				length = MSS;
			}
			else {
				segment.data = Arrays.copyOfRange(data, dataPos, size);
				lastSegment = true;
				length = size - dataPos;
			}
			
			dataPos += MSS;
			segment.seqNum = seqNumber;
			segment.ackNum = seqNumber;
			segment.length = length;
			segment.flags = 0;
			segment.checksum = segment.computeChecksum();
			seqNumber++;
			
			// put each segment into sndBuf
			segmentList.add(segment);
			
		}
		
		int segmentSent = 0;
		while(segmentSent < segmentList.size()){
			
			if(segmentList.get(segmentSent).seqNum < ackNumber){
				continue;
			}
			
			for(int i = 0; i < 3; i++){
				if(segmentSent + i < segmentList.size()){
					Utility.udp_send(segmentList.get(segmentSent + i), socket, dst_ip, dst_port);
				}
			}
			
			// schedule timeout for segment(s) 
			int timeout = 0;
			while(ackNumber <= segmentList.get(segmentSent).seqNum){
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				timeout += 100;
				if(timeout >= RTO){
					segmentSent--;
					break;
				}
			}
			
			
			segmentSent++;
		}
			
		return size;
	}

	
	// called by app
	// receive one segment at a time
	// returns number of bytes copied in buf
	public int receive (byte[] buf, int size)
	{
		//*****  complete
		RDTSegment segment = rcvBuf.getNext();
		
		for(int i = 0; i < Math.min(segment.length, size); i++){
			buf[i] = segment.data[i];
		}
		
		return Math.min(segment.length, size);
	}
	
	// called by app
	public void close() {
		// OPTIONAL: close the connection gracefully
		// you can use TCP-style connection termination process
	}
	
}  // end RDT class 


class RDTBuffer {
	public RDTSegment[] buf;
	public int size;	
	public int base;
	public int next;
	public Semaphore semMutex; // for mutual execlusion
	public Semaphore semFull; // #of full slots
	public Semaphore semEmpty; // #of Empty slots
	
	RDTBuffer (int bufSize) {
		buf = new RDTSegment[bufSize];
		for (int i=0; i<bufSize; i++)
			buf[i] = null;
		size = bufSize;
		base = next = 0;
		semMutex = new Semaphore(1, true);
		semFull =  new Semaphore(0, true);
		semEmpty = new Semaphore(bufSize, true);
	}

	
	
    // Put a segment in the next available slot in the buffer
    // used by sender 
	public void putNext(RDTSegment seg) {		
		try {
			semEmpty.acquire(); // wait for an empty slot 
			semMutex.acquire(); // wait for mutex 
				buf[next%size] = seg;
				next++;  
			semMutex.release();
			semFull.release(); // increase #of full slots
		} catch(InterruptedException e) {
			System.out.println("Buffer put(): " + e);
		}
	}
	
   // return the next in-order segment
    // used by receiver
    public RDTSegment getNext() 
    {
        RDTSegment seg = null;
        try 
        {
            semFull.acquire(); //wait for a full *in order* slot
            semMutex.acquire();
            seg = buf[base%size]; 
            buf[base%size] = null;
            base++;         
            semMutex.release();
            semEmpty.release();
        }
        catch(InterruptedException e) 
        {
            System.out.println("Buffer put(): " + e);
        }
        return seg;
    }

	
	// for debugging
	public void dump() {
		System.out.println("Dumping the receiver buffer ...");
		// Complete, if you want to 
		
	}
} // end RDTBuffer class



class ReceiverThread extends Thread {
	RDTBuffer rcvBuf, sndBuf;
	DatagramSocket socket;
	InetAddress dst_ip;
	int dst_port;
	RDT rdt;
	
	ReceiverThread (RDTBuffer rcv_buf, RDTBuffer snd_buf, DatagramSocket s, 
			InetAddress dst_ip_, int dst_port_, RDT rdt) {
		rcvBuf = rcv_buf;
		sndBuf = snd_buf;
		socket = s;
		dst_ip = dst_ip_;
		dst_port = dst_port_;
		this.rdt = rdt;
	}	
	public void run() { //From server and client side
		
		// *** complete 
		// Essentially:  while(cond==true){  // may loop for ever if you will not implement RDT::close()  
		//                socket.receive(pkt)
		//                seg = make a segment from the pkt
		//                verify checksum of seg
		//	              if seg contains ACK, process it potentailly removing segments from sndBuf
		//                if seg contains data, put the data in rcvBuf and do any necessary 
		//                             stuff (e.g, send ACK)
		//
		
		int seqNumExpected = 0;
		
		while(true){
			DatagramPacket packet = new DatagramPacket(new byte[256], 256);
			try {
				socket.receive(packet);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			RDTSegment segment = new RDTSegment();
			makeSegment(segment, packet.getData());
			
			
			
			if(segment.isValid() == false){
				System.out.println("Corrupted Packet");
				continue;
			}
			
			
			
			
			//else	
			
			if(segment.flags == 0){ //If seg contains data
				if(segment.seqNum == seqNumExpected){
					//Got expected segment
					rcvBuf.putNext(segment);
					seqNumExpected++;
				}
				
				RDTSegment ackSegment = new RDTSegment();
				ackSegment.flags = 1;
				ackSegment.ackNum = seqNumExpected;
				ackSegment.checksum = ackSegment.computeChecksum();
				Utility.udp_send(ackSegment, socket, dst_ip, dst_port);
			}
			else{ //If seg contains ack
				//TODO: you did segment.ackNum+1 == rdt.ackNumber+1
				if(segment.ackNum == rdt.ackNumber+1){
					rdt.ackNumber = segment.ackNum;
				}
				//else drop
			}
			
		}
		
	}
	
	
//	 create a segment from received bytes 
	void makeSegment(RDTSegment seg, byte[] payload) {
	
		seg.seqNum = Utility.byteToInt(payload, RDTSegment.SEQ_NUM_OFFSET);
		seg.ackNum = Utility.byteToInt(payload, RDTSegment.ACK_NUM_OFFSET);
		seg.flags  = Utility.byteToInt(payload, RDTSegment.FLAGS_OFFSET);
		seg.checksum = Utility.byteToInt(payload, RDTSegment.CHECKSUM_OFFSET);
		seg.rcvWin = Utility.byteToInt(payload, RDTSegment.RCV_WIN_OFFSET);
		seg.length = Utility.byteToInt(payload, RDTSegment.LENGTH_OFFSET);
		//Note: Unlike C/C++, Java does not support explicit use of pointers! 
		// we have to make another copy of the data
		// This is not effecient in protocol implementation
		for (int i=0; i< seg.length; i++)
			seg.data[i] = payload[i + RDTSegment.HDR_SIZE]; 
	}
	
} // end ReceiverThread class

