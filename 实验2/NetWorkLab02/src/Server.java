import service.TransferData;
import service.impl.GBNTransferData;
import service.impl.SRTransferData;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 服务器
 */
public class Server {
    //服务器的端口号
    private static final int PORT = 8081;
    //目的主机（客户端）的IP地址
    private static final String TARGET_IP = "127.0.0.1";
    //目的主机（客户端）的端口号
    private static final int TARGET_PORT = 8080;
    //接受文件的路径
    private static final String RECEIVE_FILE_PATH = "serverReceiveFile/file1.jpg";
    //发送的文件的路径
    private static final String SEND_FILE_PATH = "serverSendFile/file1.jpg";
    //目的主机（客户端）的地址
    private static final InetAddress TARGET_HOST;


    static {
        try {
            TARGET_HOST = InetAddress.getByName(TARGET_IP);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }


    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(1000);
//        TransferData GBN = new GBNTransferData(Server.PORT, TARGET_HOST, TARGET_PORT);
//        transfer(GBN);

        TransferData SR = new SRTransferData(Server.PORT, TARGET_HOST, TARGET_PORT);
        transfer(SR);
    }

    /**
     * 发送和接收文件
     * @param protocol 协议的类型
     * @throws IOException
     */
    private static void transfer(TransferData protocol){
        //1.接受文件
        System.out.println("等待从:"+ TARGET_HOST +":"+ TARGET_PORT +"接受文件");
        //2.将接受的文件写入硬盘
        ByteArrayOutputStream byteArrayOutputStream;
        try {
            if((byteArrayOutputStream = protocol.receiveData()).size() != 0) {
                File file = new File(RECEIVE_FILE_PATH);
                writeDataToFile(byteArrayOutputStream,file);
                System.out.println("接收到文件!保存路径为:"+file.getPath());
            }
        } catch (IOException e) {
            throw new RuntimeException("文件IO出现错误!");
        }
        System.out.println("=============================================");
        //3.将要发送的文件从硬盘写入到内存
        ByteArrayOutputStream fileData;
        try {
            //将文件写入到字节输出流
            fileData = Client.transferFileToStream(SEND_FILE_PATH);
        } catch (IOException e) {
            throw new RuntimeException("文件IO出现错误!");
        }
        System.out.println("正在向"+ TARGET_IP + TARGET_PORT +"发送文件......");
        System.out.println("文件路径为:"+SEND_FILE_PATH);

        //4.发送数据
        protocol.sendData(fileData);
    }


    /**
     * 将字节输出流中的数据写入到文件中去
     * @param data 数据
     * @param file 文件
     */
    public static void writeDataToFile(ByteArrayOutputStream data,File file){
        FileOutputStream fileOutputStream;
        try {
            fileOutputStream = new FileOutputStream(file);
            fileOutputStream.write(data.toByteArray(), 0, data.size());
            fileOutputStream.close();
        } catch (IOException e) {
            System.out.println("写入文件出现错误!");
            throw new RuntimeException(e);
        }
    }
}
