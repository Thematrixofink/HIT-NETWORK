

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * 客户端和服务端进行数据交换
 */
public class TransferData{
    public void transfer(Socket client, Socket server) {
        //缓存来自客户端的数据
        ByteArrayOutputStream ClientCache;
        //缓存来自服务端的数据
        ByteArrayOutputStream ServerCache;

        //不断从客户端读取数据，然后写入服务器
        //不断从服务器读取数据，然后写入客户端
        while (server != null && !(client.isClosed() || server.isClosed())) {
            try {
                ClientCache = new ByteArrayOutputStream();
                try {
                    //设置inputStream.read的等待时间
                    client.setSoTimeout(200);
                    transferData(ClientCache, client);
                } catch (SocketTimeoutException e) {

                }
                server.getOutputStream().write(ClientCache.toByteArray());

                ServerCache = new ByteArrayOutputStream();
                try {
                    server.setSoTimeout(200);
                    transferData(ServerCache, server);
                } catch (SocketTimeoutException e) {

                }

                client.getOutputStream().write(ServerCache.toByteArray());
                ClientCache.close();
                ServerCache.close();
            } catch (Exception e) {
                break;
            }
        }
    }

    /**
     * 从socket中读取数据到data输出流
     * @param data
     * @param socket
     * @throws IOException
     */
    private void transferData(ByteArrayOutputStream data, Socket socket) throws IOException {
        byte[] buffer = new byte[1024];
        int length;
        while ((length = socket.getInputStream().read(buffer)) != -1) {
            data.write(buffer, 0, length);
        }
    }
}
