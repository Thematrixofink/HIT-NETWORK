/**
 * 存储Host、Port、URL
 */
public class HostAndPortAndURL {
    private String host;
    private int port;
    private String url;

    public HostAndPortAndURL(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public HostAndPortAndURL() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    @Override
    public String toString() {
        return "host="+host+"port="+port;
    }
}
