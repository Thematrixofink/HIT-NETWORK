package service.impl;

import service.TransferData;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;


/**
 * 使用GBN进行数据传输
 */
public class GBNTransferData implements TransferData {
    //目的主机的端口号
    private final int port;
    //目的主机的地址
    private final InetAddress targetHost;
    //目的主机的端口号
    private final int targetPort;
    //发送窗口的大小
    private static final int WINDOW_SIZE = 6;
    //分组的最大数据长度（字节)
    private static final int GROUP_SIZE = 1024;
    //超时时间
    private static final int TIMEOUT = 800;
    //如果超时了,重新尝试的次数
    private static final int OUT_TIME_TRY_TIMES = 3;
    //进行模loss运算，来模拟数据丢失
    private static final int LOSS = 8;

    //窗口的起始位置
    private int windowIndex = 0;
    //已经确认的最新数据序号,比如：1,2,3都确认，那就是3
    private long sendAckSeqNum = 0;

    public GBNTransferData(int port, InetAddress targetHost, int targetPort) {
        this.port = port;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }


    /**
     * 发送数据
     * @param data 数据
     */
    @Override
    public void sendData(ByteArrayOutputStream data){
        //1.首先先将数据按照seq + data进行拆分
        byte[][] dataGroup = splitData(data,GROUP_SIZE);
        //分组的总数
        int packetNum = dataGroup.length;
        DatagramSocket clientSocket;
        try {
            clientSocket = new DatagramSocket(port);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        //当最后一个分组packetNum - 1没有确认时,就循环操作
        while(sendAckSeqNum < packetNum - 1) {
            //发送窗口里面所有的分组
            for (int i = windowIndex; i < windowIndex + WINDOW_SIZE && i < packetNum; i++) {
                DatagramPacket sendPacket = new DatagramPacket(dataGroup[i],0,dataGroup[i].length,targetHost,targetPort);
                //发送分组
                try {
                    clientSocket.send(sendPacket);
                } catch (IOException e) {
                    System.out.println("发送分组异常.....");
                    throw new RuntimeException(e);
                }
            }

            try {
                //设置超时时间
                clientSocket.setSoTimeout(TIMEOUT);
                //发送完之后等待接受ack
                while (true) {
                    byte[] receiveData = new byte[GROUP_SIZE];
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    clientSocket.receive(receivePacket);
                    //从receivePacket中拿到确认的编号
                    long receiveAckSeqNum = getSeqNum(receivePacket);
                    System.out.println("接收者已确认分组编号:"+receiveAckSeqNum);
                    //如果接收方返回来的确认分组序号是合法的，更新发送方最新的确认分组号
                    if (receiveAckSeqNum >= sendAckSeqNum && receiveAckSeqNum <= sendAckSeqNum + WINDOW_SIZE) {
                        sendAckSeqNum = receiveAckSeqNum;
                        //窗口移动
                        windowIndex = (int)sendAckSeqNum + 1;
                    }else{
                        break;
                    }
                }
            } catch (SocketTimeoutException e) {
                //当sendAckSeqNum < packetNum - 1 而且出现SocketTimeoutException的时候
                //说明确认编号错误，重新发送窗口里所有的分组
                if(sendAckSeqNum < packetNum - 1){
                    //说明传送socket超时了，那么重传窗口里的分组
                    //发送窗口里面所有的分组
                    for (int i = windowIndex; i < windowIndex + WINDOW_SIZE && i < packetNum; i++) {
                        DatagramPacket sendPacket = new DatagramPacket(dataGroup[i], 0, dataGroup[i].length, targetHost, targetPort);
                        //发送分组
                        try {
                            clientSocket.send(sendPacket);
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                }
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("全部数据已被接受!");
        //关闭连接，恢复窗口起始位置以及期望分组编号
        clientSocket.close();
        windowIndex = 0 ;
        sendAckSeqNum = 0;
    }

    /**
     * 接受数据
     * @return 返回数据所在的字节输出流
     * @throws IOException
     */
    @Override
    public ByteArrayOutputStream receiveData() throws IOException {
        //重新尝试次数
        int time = 0;
        //计数，模拟丢失分组
        int count = 0;
        // 期望接收到的分组
        long expectSeq = 0;
        //存储最后接收到的数据，交付给上层
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DatagramSocket datagramSocket = new DatagramSocket(port);
        DatagramPacket receivePacket;

        //为接收设置超时时间
        datagramSocket.setSoTimeout(TIMEOUT);
        while (true) {
            count++;
            try {
                //接受一个分组
                byte[] receive = new byte[GROUP_SIZE];
                receivePacket = new DatagramPacket(receive, receive.length, targetHost, targetPort);
                datagramSocket.receive(receivePacket);
                //从接受的数据中提取分组号
                long seq = getSeqNum(receivePacket);
                // 若不是期望接收的分组，则丢弃
                if(expectSeq != seq) continue;

                // 模拟丢包
                if(count % LOSS == 0) {
                    System.out.println("丢弃此分组~:"+seq);
                    continue;
                }

                //将收到的分组写入结果中去
                result.write(receive, 8, receivePacket.getLength() - 8);
                expectSeq++;
                //创建一个ack报文，含有确认的分组号
                ByteArrayOutputStream temp = new ByteArrayOutputStream();
                ByteBuffer longBuffer = ByteBuffer.allocate(Long.BYTES);
                longBuffer.putLong(seq);
                byte[] longTemp = longBuffer.array();
                temp.write(longTemp,0,Long.BYTES);
                byte[] seqPacket = temp.toByteArray();
                //发送ack确认分组
                receivePacket = new DatagramPacket(seqPacket, seqPacket.length, targetHost, targetPort);
                datagramSocket.send(receivePacket);
                System.out.println("接收到分组：seq " + seq);
                //如果收到了数据，计数置为0
                time = 0;
            } catch (SocketTimeoutException e) {
                //超时一次，time++
                time ++;
            }
            // 超出最大接收时间，则接收结束，写出数据
            if(time > OUT_TIME_TRY_TIMES) {
                break;
            }
        }
        //关闭连接
        datagramSocket.close();
        return result;
    }

    /**
     * 从发送的分组里面提取出来seqNum
     * @param receivePacket
     * @return
     */
    private long getSeqNum(DatagramPacket receivePacket) {
        byte[] data = receivePacket.getData();
        // 填充byteArray，确保前8个字节能够构成一个long值
        // 从字节数组中提取long值
        ByteBuffer buffer = ByteBuffer.wrap(data);
        return buffer.getLong();
    }



    /**
     * 将数据拆分,并拼接成seq + data形式
     * @param dataStream 要拆分的数据
     * @param size 分组的字节数
     * @return
     */
    private byte[][] splitData(ByteArrayOutputStream dataStream, int size) {
        byte[] data = dataStream.toByteArray();
        //分组的个数
        int numPackets = (int) Math.ceil((double) data.length / size);
        byte[][] result = new byte[numPackets][size];
        long currentSeq = 0;
        //将数据按照seq  + data的形式进行拼接
        int dataStartIndex = 0;
        //在每个分组前面都加上分组编号
        for (int i = 0; i < numPackets; i++) {
            ByteArrayOutputStream temp = new ByteArrayOutputStream();
            //将分组号写入到数据
            ByteBuffer longBuffer = ByteBuffer.allocate(Long.BYTES);
            longBuffer.putLong(currentSeq);
            byte[] longTemp = longBuffer.array();
            temp.write(longTemp,0,Long.BYTES);
            //确定结束下标，防止超过数据总长度
            int len = size - Long.BYTES;
            if(dataStartIndex + len > data.length) {
                len = data.length - dataStartIndex;
            }
            //将数据写入分组
            temp.write(data,dataStartIndex,len);
            //下一个分组在data的开始下标
            dataStartIndex = dataStartIndex + len;
            result[i] = temp.toByteArray();
            currentSeq++;
        }
        return result;
    }

}
