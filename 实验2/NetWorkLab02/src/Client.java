import service.TransferData;
import service.impl.GBNTransferData;
import service.impl.SRTransferData;

import java.io.*;
import java.net.*;

/**
 * 客户端
 */
public class Client {
    //客户端的端口号
    private static final int PORT = 8080;
    //目的主机（服务器）的IP地址
    private static final String TARGET_IP = "127.0.0.1";
    //目的主机（服务器）的端口号
    private static final int TARGET_PORT = 8081;
    //发送的文件的路径
    private static final String SEND_FILE_PATH = "clientSendFile/file1.jpg";
    //接受的文件的路径
    private static final String RECEIVE_FILE_PATH = "clientReceiveFile/file1.jpg";
    //目的主机（服务器）的地址
    public static final InetAddress TARGET_HOST;

    static {
        try {
            TARGET_HOST = InetAddress.getByName(TARGET_IP);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
//        TransferData GBN = new GBNTransferData(Client.PORT, TARGET_HOST, TARGET_PORT);
//        transfer(GBN);

        TransferData SR = new SRTransferData(Client.PORT, TARGET_HOST, TARGET_PORT);
        transfer(SR);
    }


    public static void transfer(TransferData protocol){
        //1.先将文件写入到字节输出流中去
        ByteArrayOutputStream fileData;
        try {
            //将文件写入到字节输出流
            fileData = transferFileToStream(SEND_FILE_PATH);
        } catch (IOException e) {
            throw new RuntimeException("文件IO出现错误!");
        }
        //打印日志信息
        System.out.println("正在向"+ TARGET_IP + TARGET_PORT +"发送文件......");
        System.out.println("文件路径为:"+SEND_FILE_PATH);
        //2.发送数据
        //protocol = new GBNTransferData(Client.Port, TargetHost, TargetPort);
        protocol.sendData(fileData);
        System.out.println("=============================================");
        //3.接收数据
        System.out.println("等待从:"+ TARGET_HOST +":"+ TARGET_PORT +"接受文件");
        //4.将数据写入到文件中去
        ByteArrayOutputStream byteArrayOutputStream;
        try {
            if((byteArrayOutputStream = protocol.receiveData()).size() != 0) {
                File file = new File(RECEIVE_FILE_PATH);
                Server.writeDataToFile(byteArrayOutputStream,file);
                System.out.println("接收到文件!保存路径为:"+file.getPath());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }



    /**
     * 将文件数据写入到ByteOutputStream中去
     * @param url 文件路径
     * @return 返回含有数据的ByteOutputStream
     * @throws IOException IO异常
     */
    public static ByteArrayOutputStream transferFileToStream(String url) throws IOException {
        File file = new File(url);
        if(!file.exists()){
            throw new RuntimeException("文件不存在!");
        }
        //读取文件基本操作
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        FileInputStream fileInputStream = new FileInputStream(file);
        byte[] buffer = new byte[1024];
        int length;
        while ((length = fileInputStream.read(buffer)) != -1) {
            data.write(buffer, 0, length);
        }
        fileInputStream.close();
        return data;
    }
}
