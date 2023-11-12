
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;

public class Server {

    private static final int SERVER_PORT = 8080;
    //用户过滤
    private static final HashSet<String> CLIENT_FIREWALL = new HashSet<>();

    static{
        //添加不允许访问的用户
        //CLIENT_FIREWALL.add("127.0.0.1");
    }

    public static void main(String[] args) throws IOException {
        //创建一个ServerSocket，并在8080端口进行监听
        ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
        System.out.println("创建serverSocket成功!在" + serverSocket + "监听");
        //一直让其在 8080 端口监听
        while (true) {
            Socket connect = serverSocket.accept();
            String hostName = connect.getInetAddress().getHostName();
            //用户过滤操作
            if(CLIENT_FIREWALL.contains(hostName)){
                System.out.println("当前用户禁止访问!");
            }else {
                HttpProxy httpProxy = new HttpProxy(connect);
                //开启一个新线程来处理Http请求
                new Thread(httpProxy).start();

            }
        }
    }
}
