import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    private static final int SERVER_PORT = 2357;
    private static final double PCK_LOSS_PROB = 0.1; // assume 10%
    private static final double ACK_LOSS_PROB = 0.01; // assume 1%
    private static final int RCV_BUFFER_SIZE = 1024 * 256; // as client sends a large file(video), buffer size should be large enough to reduce latency
    
    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(SERVER_PORT)) {
            serverSocket.setReceiveBufferSize(RCV_BUFFER_SIZE);
            System.out.println("Server started");
            System.out.println("Waiting for client ...");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("\nclient connected");
                new ClientHandler(clientSocket).start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static class ClientHandler extends Thread {
        private final Socket clientSocket;  
        private int rwnd = RCV_BUFFER_SIZE; // initially no data in buffer 
        private final int rcvBuffer = RCV_BUFFER_SIZE;
        private int lastByteRcvd = 0;
        private int lastByteRead = 0;
        
        private int expectedSeqNum = 1;
        private int lastAckSent = 0;
        private final Map<Integer, byte[]> outOfOrderBuffer = new HashMap<>();
        private int totalPckRcvd = 0;
        private int totalPckLost = 0;
        private int totalAckSent = 0;
        private int totalAckLost = 0;
        private int dupAckSent = 0;
        
        private ByteArrayOutputStream receivedData = new ByteArrayOutputStream();
        private String fileName = "";
        
        // Delayed ACK timer
        private Timer ackTimer = new Timer(true);
        private TimerTask pendingAckTask = null;
        private boolean hasPendingAck = false;
        
        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }
        
        @Override
        public void run() {
            try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
                 DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream())) {
            
                dos.writeUTF("Please enter filename to transfer:");
                dos.flush();
                
                fileName = dis.readUTF();
                System.out.println("Client wants to transfer: " + fileName);
                dos.writeUTF("Ready to receive ");
                dos.flush();
                
                startBufferReader();
                
                while (true) {
                    int seqNum = dis.readInt();
                    int dataLength = dis.readInt();
                    boolean isProbe = dis.readBoolean();
                    byte[] data = new byte[dataLength];
                    if (dataLength > 0) {
                        dis.readFully(data);
                    }
                    
                    // end packet
                    if (dataLength == 0 && !isProbe) {
                        System.out.println("Received END packet " + seqNum);
                        sendAck(dos, seqNum, false); //final ack
                        break;
                    }
                    
                    // probe packets (when rwnd =0)
                    if (isProbe) {
                        System.out.println("Received PROBE packet " + seqNum + " - rwnd=" + rwnd);
                        sendAck(dos, lastAckSent, false);
                        continue;
                    }
                    
                    // simulate pck loss
                    if (Math.random() < PCK_LOSS_PROB) {
                        totalPckLost++;
                        System.out.println("--- Packet " + seqNum + " LOST ---");
                        continue;
                    }
                    
                    totalPckRcvd++;
                    
                    // packet is duplicate (already processed)
                    if (seqNum < expectedSeqNum) {
                        System.out.println("Duplicate Packet " + seqNum + " received");
                        sendAck(dos, lastAckSent, true); // send duplicate ACK
                        continue;
                    }
                    
                    // check flow control - is there buffer space?
                    updateFlowControl();
                    if (rwnd <= 0) {
                        System.out.println("Buffer full! Discarding Packet " + seqNum + " (rwnd=0)");
                        sendAck(dos, lastAckSent, true); // send duplicate ACK
                        continue;
                    }
                    
                    System.out.println("Received Packet " + seqNum + " (" + dataLength + " bytes) [rwnd=" + rwnd + "]");
                    
                    if (seqNum == expectedSeqNum) {
                        processInOrderPacket(seqNum, data, dos);
                    } else if (seqNum > expectedSeqNum) {
                        processOutOfOrderPacket(seqNum, data, dos);
                    }
                }
                
                saveReceivedFile();
                printStatistics();
                
            } catch (IOException e) {
                System.err.println("Client handler error: " + e.getMessage());
            } finally {
                cleanup();
            }
        }
        
        private void startBufferReader() {
            Timer appTimer = new Timer(true);
            appTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    if (lastByteRcvd > lastByteRead) {
                        int readAmount = Math.min(1024*8, lastByteRcvd - lastByteRead);
                        lastByteRead += readAmount;
                        if (readAmount > 0) {
                            System.out.println("Application read " + readAmount + " bytes [buffer space opened]");
                        }
                    }
                }
            }, 100, 100); // read buffer every 100ms
        }
        
        private void sendAck(DataOutputStream dos, int ackNum, boolean isDuplicate) {
            try {
                // simulate ack loss 
                if (Math.random() < ACK_LOSS_PROB) {
                    totalAckLost++;  
                    System.out.println("--- ACK " + ackNum + " LOST ---");
                    return; 
                }
                
                updateFlowControl();
                
                dos.writeInt(ackNum);
                dos.writeInt(rwnd);
                dos.flush();
                totalAckSent++;  
                if (isDuplicate) {
                    dupAckSent++;  
                    System.out.println("Sent Duplicate ACK " + ackNum + " [rwnd=" + rwnd + "]");
                } else {
                    System.out.println("Sent ACK " + ackNum + " [rwnd=" + rwnd + "]");
                }
                
            } catch (IOException e) {
                System.err.println("Error sending ACK: " + e.getMessage());
            }
        }
        
        private void updateFlowControl() {
            rwnd = Math.max(0, rcvBuffer - (lastByteRcvd - lastByteRead));
            
            int bufferedBytes = outOfOrderBuffer.size() * 1024;
            rwnd = Math.max(0, rwnd - bufferedBytes);
        }
        
        private void processInOrderPacket(int seqNum, byte[] data, DataOutputStream dos) throws IOException {
            receivedData.write(data);
            lastByteRcvd = seqNum;
            expectedSeqNum++;
            
            System.out.println("In-order Packet " + seqNum + " processed");
            
            // check if we can process any buffered out-of-order packets
            while (outOfOrderBuffer.containsKey(expectedSeqNum)) {
                byte[] bufferedData = outOfOrderBuffer.remove(expectedSeqNum);
                receivedData.write(bufferedData);
                lastByteRcvd = expectedSeqNum;
                expectedSeqNum++;
                System.out.println("Processed buffered Packet " + (expectedSeqNum - 1));
            }
            
            lastAckSent = expectedSeqNum - 1;
            
            // implement delayed ack
            scheduleDelayedAck(dos);
        }
        
        private void processOutOfOrderPacket(int seqNum, byte[] data, DataOutputStream dos) throws IOException {
            if (outOfOrderBuffer.size() < rwnd) {
                outOfOrderBuffer.put(seqNum, data);
                System.out.println("Out-of-order Packet " + seqNum + " buffered");
            } else {
                System.out.println("Out-of-order buffer full! Discarding Packet " + seqNum);
            }
            
            // send duplicate ack for last in-order packet
            sendAck(dos, lastAckSent, true);
        }
        
        private void scheduleDelayedAck(DataOutputStream dos) {
            if (hasPendingAck) {
                if (pendingAckTask != null) {
                    pendingAckTask.cancel();
                }
                sendAck(dos, lastAckSent, false);
                hasPendingAck = false;
            } else {
                hasPendingAck = true;
                pendingAckTask = new TimerTask() {
                    @Override
                    public void run() {
                        sendAck(dos, lastAckSent, false);
                        hasPendingAck = false;
                    }
                };
                ackTimer.schedule(pendingAckTask, 200);
            }
        }
        
        private void saveReceivedFile() {
            try {
                String outputFileName = "received_" + fileName;
                try (FileOutputStream fos = new FileOutputStream(outputFileName)) {
                    receivedData.writeTo(fos);
                }
                System.out.println("File saved as: " + outputFileName);
            } catch (IOException e) {
                System.err.println("Error saving file: " + e.getMessage());
            }
        }
        
        private void printStatistics() {
            System.out.println("\n=== Server Statistics ===");
            System.out.println("File: " + fileName);
            System.out.println("Total packets received: " + totalPckRcvd);
            System.out.println("Total packets lost: " + totalPckLost);
            System.out.println("Total ACKs sent: " + totalAckSent);
            System.out.println("Duplicate ACKs sent: " + dupAckSent);
            System.out.println("Total ACKs lost: " + totalAckLost);
            System.out.println("Packet loss rate: " + String.format("%.2f", (double)totalPckLost/(totalPckLost+totalPckRcvd)*100) + "%");
            System.out.println("ACK loss rate: " + String.format("%.2f", (double)totalAckLost/(totalAckLost+totalAckSent)*100) + "%");
            System.out.println("Final buffer utilization: " + (lastByteRcvd - lastByteRead) + "/" + rcvBuffer + " bytes");
        }
        
        private void cleanup() {
            try {
                if (ackTimer != null) {
                    ackTimer.cancel();
                }
                clientSocket.close();
                System.out.println("Client connection closed.");
            } catch (IOException e) {
                System.err.println("Error during cleanup: " + e.getMessage());
            }
        }
    }
}
