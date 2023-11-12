
import java.io.*;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行连接请求的类
 */
public class HttpProxy implements Runnable {
    //服务端监听到的客户端的请求Socket
    private Socket clientSocket;
    //request的信息
    private String strHeader;
    private HashMap<String,String> headers;
    private String body;
    private String method;
    private HostAndPortAndURL hostAndPort;
    //代理Socket类
    private Socket proxyClient;
    //网站过滤防火墙
    private static final HashSet<String> SERVER_FIREWALL = new HashSet<>();

    public HttpProxy(Socket clientSocket) {
        this.clientSocket = clientSocket;
        this.proxyClient  = null;
        headers = new HashMap<>();
        hostAndPort = new HostAndPortAndURL();
        method = null;
        body = null;
        strHeader = null;
        //添加要过滤的网站
        //todo 通过外部文件配置形式
        //SERVER_FIREWALL.add("today.hit.edu.cn");
    }


    //收到连接请求之后，开启线程，执行run方法
    @Override
    public void run() {
        //在这个流中，存储从客户端接收到的HTTP请求或其他数据。
        ByteArrayOutputStream clientSocketInfo = new ByteArrayOutputStream();
        try {
            //设置超时时间
            clientSocket.setSoTimeout(400);
            //将socket中的数据写入到clientSocketInfo
            storageRequestInfo(clientSocketInfo,clientSocket.getInputStream());
        } catch (SocketTimeoutException e){

        } catch (IOException e) {
            throw new RuntimeException("储存请求信息失败!");
        }

        try {
            //获取header以及body的信息
            getHeaderAndBodyInfo(clientSocketInfo);
            String targetHost = hostAndPort.getHost();
            int targetPort = hostAndPort.getPort();

            //检查防火墙
            if(SERVER_FIREWALL.contains(targetHost)){
                System.out.println("防火墙不允许你访问当前主机!");
                CloseAllConnect();
            }

            //如果是某一个网站，就钓鱼
            if( hostAndPort.getHost()!=null && hostAndPort.getHost().equals("news.hit.edu.cn")){
                System.out.println("引导用户访问至:today.hit.edu.cn");
                String response = "HTTP/1.1 302 Found\r\nLocation: http://today.hit.edu.cn";
                clientSocket.getOutputStream().write(response.getBytes());
                clientSocket.close();
            }

            // 创建代理连接到目标服务器
            proxyClient = new Socket(targetHost, targetPort);

            if (targetPort == 80) {
                System.out.println("检测到HTTP请求: " + targetHost);

                //得到缓存文件的最新修改时间
                String url = hostAndPort.getUrl();
                if(url == null){
                    proxyClient.getOutputStream().write((strHeader+"\r\n\r\n"+body).getBytes());
                    proxyClient.getOutputStream().flush();
                }
                else {
                    String cacheName = url.replaceAll("[^A-Za-z0-9]", "");
                    File cache = new File("cache/" + cacheName);
                    //如果有缓存，那么就查询是不是最新的
                    if (cache.exists()) {
                        String createCacheTime = TransferDataWithCache.getCreateCacheTime(cache);
                        // Day, DD Month YYYY HH:MM:SS GMT
                        // 添加 If-Modified-Since 请求头的新请求
                        //If-Modified-Since: Thu, 31 Aug 2023 02:44:38 GMT
                        byte[] newRequest = (strHeader + "If-Modified-Since: " + createCacheTime + "\r\n\r\n" + body).getBytes();
                        ByteArrayOutputStream a = new ByteArrayOutputStream();
                        a.write(newRequest);
                        proxyClient.getOutputStream().write(a.toByteArray());
                        proxyClient.getOutputStream().flush();
                    } else {
                        proxyClient.getOutputStream().write((strHeader + "\r\n\r\n" + body).getBytes());
                        proxyClient.getOutputStream().flush();
                    }
                }
            } else if (targetPort == 443) {
                //System.out.println("检测到HTTPS请求，暂不处理....");
                System.out.println("检测到HTTPS请求: " + targetHost);
                //发送特殊响应，告诉客户端，与代理服务器的连接已经成功建立，客户端可以开始进行TLS/SSL握手。
                //客户端接收到这个响应后，会发送TLS/SSL握手请求，建立一个安全通道，然后通过该通道进行加密通信。
                //这里是clientSocket!!!
                clientSocket.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes());
                proxyClient.getOutputStream().flush();
            }

            //设置超时时间，避免阻塞
            if (proxyClient != null)
                proxyClient.setSoTimeout(200);

            //数据传输
            TransferData transferData;
            if (targetPort == 80) {
                //缓存版本
                TransferDataWithCache transferDataWithCache = new TransferDataWithCache();
                transferDataWithCache.transfer(clientSocket,proxyClient,hostAndPort);


            } else if (targetPort == 443) {
                transferData = new TransferData();
                transferData.transfer(clientSocket, proxyClient);
            }
            //关闭连接
            CloseAllConnect();

        } catch (ConnectException e) {
            System.err.println(e.getMessage());
            CloseAllConnect();
        } catch (UnknownHostException e) {
            System.err.println("未知的主机名 " + e.getMessage());
            CloseAllConnect();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    /**
     * 将request的信息先存储起来，便于我们进行操作
     * @param clientSocketInfo
     * @param inputStream
     * @throws IOException
     */
    private void storageRequestInfo(ByteArrayOutputStream clientSocketInfo, InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            clientSocketInfo.write(buffer, 0, length);
        }
    }

    /**
     * 拿到请求头的信息
     * @param clientSocketInfo
     * @throws IOException
     */
    private void getHeaderAndBodyInfo(ByteArrayOutputStream clientSocketInfo) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(clientSocketInfo.toByteArray())));
        String line;
        boolean isFirst = true;
        StringBuilder header = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            //如果读到了一行空的，就说明header读完了
            if (line.isEmpty()) {
                break;
            }
            header.append(line).append("\r\n");
            //处理第一行  GET today.hit.edu.cn:80  HTTP/1.1
            if (isFirst) {
                Pattern urlPattern = Pattern.compile(" (.*?) ");
                Matcher urlMather = urlPattern.matcher(line);
                Pattern methodPattern = Pattern.compile("(CONNECT|GET|PUT|DELETE|POST)");
                Matcher methodMather = methodPattern.matcher(line);
                //获取请求方式
                if (methodMather.find()) {
                    this.method = methodMather.group();
                }
                if(urlMather.find()){
                    String group = urlMather.group(1);
                    //if(group.length() <200) {
                        this.hostAndPort.setUrl(group);
                    //}
                }
                isFirst = false;
            } else {
                String[] keyAndValue = line.split(": ");
                if (keyAndValue.length == 2) {
                    this.headers.put(keyAndValue[0], keyAndValue[1]);
                }
            }
        }
        strHeader = header.toString();
        //获取host和port
        if(headers.containsKey("Host")){
            String host = headers.get("Host");
            String[] split = host.split(":");
            //HTTPS请求格式： www.baidu.com:443
            if(split.length == 2) {
                this.hostAndPort.setHost(split[0]);
                this.hostAndPort.setPort(Integer.parseInt(split[1]));
            }
            //HTTP请求格式：  Host: hit.edu.cn
            else if (split.length == 1){
                this.hostAndPort.setHost(split[0]);
                this.hostAndPort.setPort(80);
            }
        }
        //获取body信息
        StringBuilder body = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }
        this.body = body.toString();
    }


    /**
     * 关闭所有的连接
     */
    private void CloseAllConnect() {
        try {
            if (clientSocket != null && !clientSocket.isClosed())
                clientSocket.close();
            if (proxyClient != null && !proxyClient.isClosed())
                proxyClient.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
