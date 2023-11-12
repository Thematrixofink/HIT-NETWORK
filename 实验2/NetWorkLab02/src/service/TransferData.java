package service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public interface TransferData {
    /**
     * 发送数据
     * @param data 数据
     */
    void sendData(ByteArrayOutputStream data);

    /**
     * 接收数据
     * @return 数据
     */
    ByteArrayOutputStream receiveData() throws IOException;
}
