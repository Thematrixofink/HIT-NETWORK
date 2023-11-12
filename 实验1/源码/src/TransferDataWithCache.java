import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 带有缓存的数据传输
 */
public class TransferDataWithCache {
    private String status;
    private HashMap<String, String> headers;
    private String strHeader;
    private String body;
    private long MaxWaitTime = 8000;

    public TransferDataWithCache() {
        status = null;
        strHeader = null;
        headers = new HashMap<>();
        body = null;
    }

    public void transfer(Socket client, Socket proxyClient, HostAndPortAndURL hostAndPortAndURL) {
        long LatestDataTransportTime = System.currentTimeMillis();
        try {
            while (proxyClient != null && !(client.isClosed() || proxyClient.isClosed())) {
                //1.proxyClient接受服务器的返回信息
                ByteArrayOutputStream serverInfo = new ByteArrayOutputStream();
                try {
                    proxyClient.setSoTimeout(200);
                    transferData(serverInfo, proxyClient.getInputStream());
                } catch (SocketTimeoutException e) {

                }
                handleResponse(serverInfo);
                //如果body不为空
                if (body != null && !body.isEmpty()) {
                    //2.如果是HTTP状态码 200
                    if (status.equals("200")) {
                        String cacheName = hostAndPortAndURL.getUrl().replaceAll("[^A-Za-z0-9]", "");
                        File cache = new File("cache/" + cacheName);
                        //2.1缓存过期了，body里面有资源的最新版本，更新缓存
                        //2.2更新资源更新时间
                        if (hostAndPortAndURL.getUrl() != null) {
                            //todo 异步处理
                            cache.createNewFile();
                            System.out.println("保存缓存:" + cacheName);
                            setCacheToFile(cache, serverInfo, body);
                        }
                        //2.3将资源返回给客户端
                        client.getOutputStream().write(serverInfo.toByteArray());
                        client.getOutputStream().flush();
                    }
                    //2.2.如果是HTTP状态码 304 Not Modified,那么就返回缓存给客户端即可
                    else if (status.equals("304")) {
                        String cacheName =hostAndPortAndURL.getUrl().replaceAll("[^A-Za-z0-9]", "");
                        File cacheFile = new File("cache/" + cacheName);
                        FileInputStream fileInputStream = new FileInputStream(cacheFile);
                        ByteArrayOutputStream cacheFromFile = getCacheFromFile(fileInputStream);
                        //发送给客户端
                        client.getOutputStream().write(cacheFromFile.toByteArray());
                        client.getOutputStream().flush();
                    } else {
                        System.out.println("状态码" + status);
                        client.getOutputStream().write(serverInfo.toByteArray());
                        client.getOutputStream().flush();
                    }
                    LatestDataTransportTime = System.currentTimeMillis();
                }
                //如果body为空的话，直接返回
                else {
                    //如果超时了
                    if (System.currentTimeMillis() - LatestDataTransportTime > MaxWaitTime) {
                        break;
                    }
                    client.getOutputStream().write(serverInfo.toByteArray());
                    client.getOutputStream().flush();
                }
            }

            client.close();
            proxyClient.close();

        } catch (IOException e) {
            try {
                if (!client.isClosed()) {
                    client.close();
                }
                if (!proxyClient.isClosed()) {
                    proxyClient.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 获得文件创建的请求头日期
     *
     * @param cache
     * @return
     * @throws IOException
     */
    public static String getCreateCacheTime(File cache) throws IOException {
        Path path = Paths.get(cache.getAbsolutePath());
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        FileTime fileTime = attrs.lastModifiedTime();
        long millis = fileTime.toMillis();
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        Date date = new Date();
        date.setTime(millis);
        return dateFormat.format(date);
    }

    /**
     * 将缓存写入到文件中去
     *
     * @param cache
     * @param body
     * @throws IOException
     */
    private void setCacheToFile(File cache, ByteArrayOutputStream body, String bodyInfo) throws IOException {
        FileOutputStream fileOutputStream = new FileOutputStream(cache);
        fileOutputStream.write(body.toByteArray());
        fileOutputStream.flush();
        fileOutputStream.close();
    }


    /**
     * 从文件中读取缓存的信息
     *
     * @param fileInputStream
     * @return
     * @throws IOException
     */
    private ByteArrayOutputStream getCacheFromFile(FileInputStream fileInputStream) throws IOException {
        ByteArrayOutputStream cache = new ByteArrayOutputStream();
        cache.write((strHeader + "\r\n\r\n").getBytes());
        byte[] temp = new byte[1024];
        int length;
        while ((length = fileInputStream.read(temp)) != -1) {
            // bytesRead 包含了实际读取的字节数
            // 将读取的数据转换为字符串并打印
            cache.write(temp, 0, length);
        }
        fileInputStream.close();
        return cache;
    }

    /**
     * 处理Http响应
     *
     * @param serverInfo
     * @throws IOException
     */
    private void handleResponse(ByteArrayOutputStream serverInfo) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(serverInfo.toByteArray())));

        String line;

        boolean isFirst = true;
        StringBuilder header = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            //如果读到了一行空的，就说明header读完了
            if (line.isEmpty()) {
                break;
            }
            header.append(line).append("\r\n");
            //处理第一行  HTTP/1.1 状态码  abcd
            if (isFirst) {
                Pattern methodPattern = Pattern.compile("\\d\\d\\d");
                Matcher methodMather = methodPattern.matcher(line);
                //获取返回的状态码
                if (methodMather.find()) {
                    this.status = methodMather.group();
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
        //获取body信息
        StringBuilder body = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }
        this.body = body.toString();
    }

    /**
     * 从socket中读取数据到data输出流
     *
     * @param data
     * @throws IOException
     */
    private void transferData(ByteArrayOutputStream data, InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            data.write(buffer, 0, length);
        }
    }
}
