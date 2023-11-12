package service.impl;

import service.TransferData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;


public class SRTransferData implements TransferData {
    private final int port;
    //目的主机的地址
    private final InetAddress targetHost;
    //目的主机的端口号
    private final int targetPort;
    //窗口的大小
    private static final int WINDOW_SIZE = 6;
    //序号的数目
    private static final int SEQ_NUM = 2 * WINDOW_SIZE;
    //分组的最大数据长度（字节)
    private static final int GROUP_SIZE = 1024;
    //超时时间
    private static final int TIMEOUT = 800;
    //如果超时了,重新尝试的次数
    private static final int OUT_TIME_TRY_TIMES = 3;
    //进行模loss运算，来模拟数据丢失
    private static final int LOSS = 8;
    //窗口里面的元素是否已被确认
    private final List<Boolean> allPacket = new ArrayList<>();

    //窗口的起始位置
    private int windowIndex = 0;
    //已经确认的最新数据序号,比如：1,2,3都确认，那就是3
    private long sendAckSeqNum = 0;


    public SRTransferData(int port, InetAddress targetHost, int targetPort) {
        this.port = port;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    @Override
    public void sendData(ByteArrayOutputStream data) {
        //1.首先先将数据进行拆分成 seq + data 的形式
        byte[][] dataGroup = splitData(data, GROUP_SIZE);
        //分组的总数
        int packetNum = dataGroup.length;
        //将所有分组，全部设置为未验证
        for (int i = 0; i < packetNum; i++) {
            allPacket.add(false);
        }
        DatagramSocket clientSocket;
        try {
            clientSocket = new DatagramSocket(port);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        //直到所有分组全被确认
        while (!windowAllACK(allPacket)) {
            //发送窗口里没有被确认的分组
            for (int i = windowIndex; i < windowIndex + WINDOW_SIZE && i < packetNum; i++) {
                if (!allPacket.get(i)) {
                    DatagramPacket sendPacket = new DatagramPacket(dataGroup[i], 0, dataGroup[i].length, targetHost, targetPort);
                    try {
                        clientSocket.send(sendPacket);
                    } catch (IOException e) {
                        System.out.println("发送分组异常.....");
                        throw new RuntimeException(e);
                    }
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
                    System.out.println("接收者已确认分组编号:" + receiveAckSeqNum);
                    //如果接收方返回来的确认分组序号是合法的，确认对应分组
                    if (receiveAckSeqNum >= windowIndex && receiveAckSeqNum <allPacket.size() && receiveAckSeqNum <= windowIndex + WINDOW_SIZE) {
                        //确认对应分组
                        allPacket.set((int) receiveAckSeqNum, true);
                        //如果下届被确认了，那么向前移动窗口
                        while (windowIndex < allPacket.size() && allPacket.get(windowIndex)) {
                            windowIndex++;
                            sendAckSeqNum++;
                        }
                    } else {
                        break;
                    }
                }
            }
            //如果接收超时，重新发送窗口里没有被确认的分组
            catch (SocketTimeoutException e) {
                if (sendAckSeqNum < packetNum - 1) {
                    //重传窗口里没有被确认的分组
                    for (int i = windowIndex; i <= windowIndex + WINDOW_SIZE && i < packetNum; i++) {
                        if (!allPacket.get(i)) {
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
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
        System.out.println("全部数据已被接受!");
        //关闭连接，将窗口位置以及确认号设置为0，为下次发送数据做准备
        clientSocket.close();
        windowIndex = 0;
        sendAckSeqNum = 0;
        //清空保存信息的集合
        allPacket.clear();
    }

    /**
     * 判断窗口中所有的分组是否全部已被确认
     * @param window 窗口
     * @return 如果全被确认，返回true，否则返回false
     */
    private boolean windowAllACK(List<Boolean> window) {
        for (Boolean a : window) {
            if (!a) return false;
        }
        return true;
    }

    @Override
    public ByteArrayOutputStream receiveData() throws IOException {
        //缓存发送方发过来的乱序的分组数据
        HashMap<Integer, ByteArrayOutputStream> receiveCache = new HashMap<>();
        //接收方的窗口
        LinkedHashMap<Integer, Boolean> receiveWindow = new LinkedHashMap<>();
        //初始化接收方窗口
        //W+1<=2L 我们序号数直接选择2倍窗口大小
        for (int i = 0; i < SEQ_NUM; i++) {
            receiveWindow.put(i, false);
        }
        //期望接受的分组,也可以看做是下届
        long receiveBase = 0;

        //超时和计数模拟丢失
        int time = 0;
        int count = 0;

        // 按序输出流
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        // server监听socket
        DatagramSocket datagramSocket = new DatagramSocket(port);
        DatagramPacket receivePacket;

        //为接收设置超时时间
        datagramSocket.setSoTimeout(TIMEOUT);
        while (true) {
            count++;
            try {
                //接收一个分组到receive中去
                ByteArrayOutputStream receive = new ByteArrayOutputStream();
                byte[] recv = new byte[1024];
                receivePacket = new DatagramPacket(recv, recv.length, targetHost, targetPort);
                datagramSocket.receive(receivePacket);
                //获取数据中的分组号
                long seq = getSeqNum(receivePacket);

                // 检测发回来的分组是不是期望的
                if (seq < receiveBase || seq > receiveBase + WINDOW_SIZE - 1) continue;
                //如果已被确认了continue
                if (receiveWindow.containsKey((int)seq % SEQ_NUM) && receiveWindow.get((int) seq % SEQ_NUM)) continue;

                // 模拟丢包
                if (count % LOSS == 0) {
                    System.out.println("丢弃此分组~："+seq);
                    continue;
                }

                //构建ACK报文，确认收到某分组
                receive.write(recv, 8, receivePacket.getLength() - 8);
                ByteArrayOutputStream temp = new ByteArrayOutputStream();
                ByteBuffer longBuffer = ByteBuffer.allocate(Long.BYTES);
                longBuffer.putLong(seq);
                byte[] longTemp = longBuffer.array();
                temp.write(longTemp, 0, Long.BYTES);
                byte[] seqPacket = temp.toByteArray();
                //发送ack确认报文
                receivePacket = new DatagramPacket(seqPacket, seqPacket.length, targetHost, targetPort);
                datagramSocket.send(receivePacket);
                System.out.println("接收到分组：seq " + seq);
                receiveCache.put((int) seq, receive);
                //窗口中确认分组
                receiveWindow.replace((int)seq % SEQ_NUM, true);
                //如果序号等于下届，那么就传输数据
                if (seq == receiveBase) {
                    int begin = (int) seq;
                    //如果下届始终被确认，那么就一直发送
                    while (receiveWindow.containsKey(begin % SEQ_NUM) && receiveWindow.get(begin % SEQ_NUM)){
                        result.write(receiveCache.get(begin).toByteArray());
                        //发送完就删了
                        receiveCache.remove(begin);
                        //窗口向前滚动
                        receiveWindow.replace(begin % SEQ_NUM,false);
                        begin++;
                        receiveBase++;
                    }
                } else {
                    //将分组先缓存起来
                    receiveCache.put((int) seq, receive);
                }

                time = 0;
            } catch (SocketTimeoutException e) {
                time++;
            }
            // 如果超时了，接收结束
            if (time > OUT_TIME_TRY_TIMES) {
                break;
            }

        }
        datagramSocket.close();
        return result;
    }

    /**
     * 从发送的分组里面提取出来seqNum
     *
     * @param receivePacket
     * @return
     */
    private long getSeqNum(DatagramPacket receivePacket) {
        byte[] data = receivePacket.getData();
        // 填充byteArray，确保前8个字节能够构成一个long值
        // 从字节数组中提取long值
        ByteBuffer buffer = ByteBuffer.wrap(data);
        long seq = buffer.getLong();
        return seq;
    }


    /**
     * 将数据拆分,并拼接成seq + data形式
     *
     * @param dataStream 要拆分的数据
     * @param size       分组的字节数
     * @return
     */
    private byte[][] splitData(ByteArrayOutputStream dataStream, int size) {
        byte[] data = dataStream.toByteArray();
        //得到数据分组的个数
        int numPackets = (int) Math.ceil((double) data.length / size);
        byte[][] result = new byte[numPackets][size];
        long currentSeq = 0;
        //将数据按照seq  + data的形式进行拼接
        int dataStartIndex = 0;
        for (int i = 0; i < numPackets; i++) {
            ByteArrayOutputStream temp = new ByteArrayOutputStream();
            ByteBuffer longBuffer = ByteBuffer.allocate(Long.BYTES);
            longBuffer.putLong(currentSeq);
            byte[] longTemp = longBuffer.array();
            temp.write(longTemp, 0, Long.BYTES);
            int len = size - Long.BYTES;
            if (dataStartIndex + len > data.length) {
                len = data.length - dataStartIndex;
            }
            temp.write(data, dataStartIndex, len);
            dataStartIndex = dataStartIndex + len;
            result[i] = temp.toByteArray();
            currentSeq++;
        }
        return result;
    }
}
