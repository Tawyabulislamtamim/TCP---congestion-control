import java.io.*;
import java.net.*;
import java.util.*;

public class Client {
    private static final String SRV_ADDR = "localhost";
    private static final int SRV_PORT = 2357;
    private static final int CHUNK_SIZE = 1024*5;
    private static final int PERSIST_PROBE_INTERVAL = 1000; 
    private static final int MAX_WINDOW_SIZE = 128;
    
    // RTT parameters
    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;
    private double estRTT = 1000;
    private double devRTT = 100;
    private double timeoutInt = 1400;
    
    // congestion control variables
    private int ssthresh = MAX_WINDOW_SIZE; // slow start threshold
    private int cwnd = 1; // congestion window (start small)
    private int rwnd = MAX_WINDOW_SIZE;
    private int effectiveWnd = 1; // min(rwnd, cwnd)
    private boolean persistMode = false;
    private long lastProbeTime = 0;
    private boolean inFastRecovery = false;
    private int recoveryPoint = 0;
    private boolean useTahoe = true; // Set to false for Reno
    
    // tracking variables
    private final Map<Integer, Long> sentTimeMap = new HashMap<>();
    private final Map<Integer, byte[]> unAckPktMap = new HashMap<>();
    private int lastByteSent = 0;
    private int lastByteAcked = 0;
    private int nextSeq = 1;
    private int dupAckCount = 0;
    private int totalRetrans = 0;
    private int totalPckSent = 0;
    
    private long lastTimeoutCheck = 0;
    private static final long TIMEOUT_CHECK_INTERVAL = 50; // check every 50ms
    
    private List<byte[]> fileChunks = new ArrayList<>();
    private String fileName = "";
    
    private void sendPacket(DataOutputStream dos, int seq, byte[] data, boolean isProbe) throws IOException {
        dos.writeInt(seq);
        dos.writeInt(data.length);
        dos.writeBoolean(isProbe);
        if (data.length > 0) {
            dos.write(data);
        }
        dos.flush();
        
        sentTimeMap.put(seq, System.currentTimeMillis());
        unAckPktMap.put(seq, data);
        totalPckSent++;
        
        if (isProbe) {
            System.out.println("Sent PROBE Packet " + seq + " (1 byte)");
        } else {
            System.out.println("Sent Packet " + seq + " (" + data.length + " bytes) [rwnd=" + rwnd + 
                             ", cwnd=" + cwnd + ", ssthresh=" + ssthresh + "]");
        }
    }
    
    private void sendProbe(DataOutputStream dos) throws IOException {
        if (System.currentTimeMillis() - lastProbeTime >= PERSIST_PROBE_INTERVAL) {
            byte[] probeData = {0}; // 1-byte probe
            sendPacket(dos, nextSeq, probeData, true);
            lastProbeTime = System.currentTimeMillis();
            System.out.println("Sent persist probe - waiting for rwnd > 0");
        }
    }
    
    private void sendEndPacket(DataOutputStream dos, int seq) throws IOException {
        dos.writeInt(seq);
        dos.writeInt(0); // zero length indicates end
        dos.writeBoolean(false); // not a probe
        dos.flush();
        
        sentTimeMap.put(seq, System.currentTimeMillis());
        unAckPktMap.put(seq, new byte[0]);
        System.out.println("Sent END Packet " + seq);
    }
    
    private void resendPacket(DataOutputStream dos, int seq) throws IOException {
        if (unAckPktMap.containsKey(seq)) {
            System.out.println("Resending Packet " + seq);
            byte[] data = unAckPktMap.get(seq);
            if (data.length == 0) {
                sendEndPacket(dos, seq);
            } else {
                sendPacket(dos, seq, data, false);
            }
            totalRetrans++;
            
            // congestion control: reduce cwnd on timeout
            if (useTahoe) {
                // Tahoe: on any loss, set ssthresh to half cwnd and reset cwnd to 1
                ssthresh = Math.max(2, cwnd / 2);
                cwnd = 1;
                inFastRecovery = false;
            } else {
                // Reno: on timeout, same as Tahoe
                ssthresh = Math.max(2, cwnd / 2);
                cwnd = 1;
                inFastRecovery = false;
            }
            updateEffectiveWindow();
        }
    }
    
    private void checkTimeouts(DataOutputStream dos) throws IOException {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastTimeoutCheck < TIMEOUT_CHECK_INTERVAL) {
            return;
        }
        lastTimeoutCheck = currentTime;
        
        // find oldest unacked packet for timeout
        int oldestSeq = Integer.MAX_VALUE;
        long oldestTime = Long.MAX_VALUE;
        
        for (Map.Entry<Integer, Long> entry : sentTimeMap.entrySet()) {
            if (entry.getKey() < oldestSeq) {
                oldestSeq = entry.getKey();
                oldestTime = entry.getValue();
            }
        }
        
        if (oldestSeq != Integer.MAX_VALUE && 
            currentTime - oldestTime > timeoutInt) {
            System.out.println("Timeout for Packet " + oldestSeq + 
                             " (waited " + (currentTime - oldestTime) + "ms)");
            resendPacket(dos, oldestSeq);
        }
    }
    
    private void processAck(int ack, int newRwnd, DataOutputStream dos) throws IOException {
        long now = System.currentTimeMillis();
        
        // update receiver window
        rwnd = newRwnd;
        
        // check if we can exit persist mode
        if (persistMode && rwnd > 0) {
            persistMode = false;
            System.out.println("Exiting persist mode - rwnd = " + rwnd);
        }
        
        // new ack 
        if (ack > lastByteAcked) {
            int newlyAckedBytes = ack - lastByteAcked;
            lastByteAcked = ack;
            dupAckCount = 0;
            
            if (inFastRecovery) {
                // Fast Recovery: exit when we get ACK for recovery point
                if (ack >= recoveryPoint) {
                    inFastRecovery = false;
                    cwnd = ssthresh; // set cwnd to ssthresh
                    System.out.println("Exiting Fast Recovery");
                } else if (!useTahoe) {
                    // Reno: for each additional ACK in fast recovery, increase cwnd by 1
                    cwnd += newlyAckedBytes;
                }
            } else {
                // Slow Start or Congestion Avoidance
                if (cwnd < ssthresh) {
                    // Slow Start: exponential increase
                    cwnd += newlyAckedBytes;
                    System.out.println("Slow Start: cwnd increased to " + cwnd);
                } else {
                    // Congestion Avoidance: linear increase
                    cwnd += Math.max(1, newlyAckedBytes * newlyAckedBytes / cwnd);
                    System.out.println("Congestion Avoidance: cwnd increased to " + cwnd);
                }
            }
            
            cwnd = Math.min(cwnd, MAX_WINDOW_SIZE);
            updateEffectiveWindow();
            
            // calculate RTT if we have timing info
            if (sentTimeMap.containsKey(ack)) {
                long sampleRTT = now - sentTimeMap.get(ack);
                updateRTT(sampleRTT);
                System.out.printf("ACK %d received. rwnd=%d, cwnd=%d, ssthresh=%d, RTT=%dms, TO=%.0fms%n",
                        ack, rwnd, cwnd, ssthresh, sampleRTT, timeoutInt);
            }
            
            // clean up acknowledged packets
            Iterator<Integer> it = unAckPktMap.keySet().iterator();
            while (it.hasNext()) {
                int seq = it.next();
                if (seq <= ack) {
                    it.remove();
                    sentTimeMap.remove(seq);
                }
            }
        }
        // duplicate ACK
        else if (ack == lastByteAcked && ack > 0) {
            dupAckCount++;
            System.out.println("Duplicate ACK " + ack + " (count: " + dupAckCount + ")");
            
            // fast retransmit on 3 duplicate ACKs
            if (dupAckCount == 3) {
                int nextSeqToRetransmit = ack + 1;
                if (unAckPktMap.containsKey(nextSeqToRetransmit)) {
                    System.out.println("Fast Retransmit triggered for Packet " + nextSeqToRetransmit);
                    resendPacket(dos, nextSeqToRetransmit);
                    
                    if (useTahoe) {
                        // Tahoe: same as timeout
                        ssthresh = Math.max(2, cwnd / 2);
                        cwnd = 1;
                        inFastRecovery = false;
                    } else {
                        // Reno: Fast Recovery
                        ssthresh = Math.max(2, cwnd / 2);
                        cwnd = ssthresh + 3; // inflate by number of duplicate ACKs
                        recoveryPoint = lastByteAcked;
                        inFastRecovery = true;
                        System.out.println("Entering Fast Recovery");
                    }
                    updateEffectiveWindow();
                }
                dupAckCount = 0;
            } else if (inFastRecovery && !useTahoe) {
                // Reno: for each additional duplicate ACK in fast recovery, increase cwnd by 1
                cwnd++;
                updateEffectiveWindow();
            }
        }
    }
    
    private void updateEffectiveWindow() {
        effectiveWnd = Math.min(rwnd, cwnd);
        
        // enter persist mode if receiver window is zero
        if (rwnd == 0 && !persistMode) {
            persistMode = true;
            System.out.println("Entering persist mode - rwnd = 0");
        }
    }
    
    private void updateRTT(long sampleRTT) {
        estRTT = (1 - ALPHA) * estRTT + ALPHA * sampleRTT;
        devRTT = (1 - BETA) * devRTT + BETA * Math.abs(sampleRTT - estRTT);
        timeoutInt = estRTT + 4 * devRTT;
        
        // ensure minimum timeout
        timeoutInt = Math.max(timeoutInt, 200); // At least 200ms
    }
    
    private void readFileIntoChunks(String fileName) throws IOException {
        File file = new File(fileName);
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + fileName);
        }
        
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            
            System.out.println("Reading file: " + fileName + " (Size: " + file.length() + " bytes)");
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                fileChunks.add(chunk);
            }
        }
        
        System.out.println("File divided into " + fileChunks.size() + " chunks");
    }
    
    private boolean canSendData() {
        return (lastByteSent - lastByteAcked) < effectiveWnd && !persistMode;
    }
    
    public static void main(String[] args) {
        new Client().start();
    }
    
    public void start() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("Select congestion control algorithm:");
        System.out.println("1. TCP Tahoe (default)");
        System.out.println("2. TCP Reno");
        System.out.print("Enter choice (1 or 2): ");
        int choice = scanner.nextInt();
        scanner.nextLine(); // consume newline
        useTahoe = (choice != 2);
        
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(SRV_ADDR, SRV_PORT), 10000);
            socket.setSoTimeout(100); // Short timeout for non-blocking reads
            
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            
            System.out.println("Connected to Server");
            System.out.println("Using " + (useTahoe ? "TCP Tahoe" : "TCP Reno") + " congestion control");
            
            // initial handshake
            String prompt = dis.readUTF();
            System.out.println("Server: " + prompt);
            
            System.out.print("Enter filename to transfer: ");
            fileName = scanner.nextLine().trim();
            
            dos.writeUTF(fileName);
            dos.flush();
            
            String serverResponse = dis.readUTF();
            System.out.println("Server: " + serverResponse);
            
            // read file
            try {
                readFileIntoChunks(fileName);
            } catch (IOException e) {
                System.err.println("Error reading file: " + e.getMessage());
                return;
            }
            
            if (fileChunks.isEmpty()) {
                System.out.println("File is empty or could not be read");
                return;
            }
            
            System.out.println("Starting reliable data transfer with flow control...");
            
            int totalPackets = fileChunks.size();
            
            // main transfer loop
            while (lastByteAcked < totalPackets) {
                // send new packets if flow control allows
                while (nextSeq <= totalPackets && canSendData()) {
                    sendPacket(dos, nextSeq, fileChunks.get(nextSeq - 1), false);
                    lastByteSent = nextSeq;
                    nextSeq++;
                }
                
                // handle persist mode
                if (persistMode) {
                    sendProbe(dos);
                }
                
                // check for timeouts
                checkTimeouts(dos);
                
                // process incoming ACKs
                try {
                    while (dis.available() > 0) {
                        int ack = dis.readInt();
                        int receivedRwnd = dis.readInt();
                        processAck(ack, receivedRwnd, dos);
                    }
                } catch (SocketTimeoutException ignored) {}
                
                Thread.sleep(5); // small delay to prevent busy waiting
            }
            
            // send end packet
            sendEndPacket(dos, nextSeq);
            
            // wait for final ACK
            long endTime = System.currentTimeMillis() + 5000; // 5 second timeout
            while (System.currentTimeMillis() < endTime) {
                try {
                    if (dis.available() > 0) {
                        int ack = dis.readInt();
                        int receivedRwnd = dis.readInt();
                        if (ack >= totalPackets) {
                            break;
                        }
                    }
                } catch (SocketTimeoutException ignored) {}
                Thread.sleep(10);
            }
            
            System.out.println("\n=== Transfer Statistics ===");
            System.out.println("File: " + fileName);
            System.out.println("Total packets: " + totalPackets);
            System.out.println("Total packets sent: " + totalPckSent);
            System.out.println("Total retransmissions: " + totalRetrans);
            System.out.println("Final EstimatedRTT: " + String.format("%.1f", estRTT) + "ms");
            System.out.println("Final cwnd: " + cwnd);
            System.out.println("Final ssthresh: " + ssthresh);
            System.out.println("Transfer completed successfully!");
            
        } catch (IOException | InterruptedException e) {
            System.err.println("Client Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            scanner.close();
        }
    }
}
