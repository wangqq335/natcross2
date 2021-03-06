package person.pluto.natcross2.api.socketpart;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.StringUtils;

import lombok.extern.slf4j.Slf4j;
import person.pluto.natcross2.api.IBelongControl;
import person.pluto.natcross2.api.passway.SimplePassway;
import person.pluto.natcross2.model.HttpRoute;

/**
 * <p>
 * http路由socket对
 * </p>
 *
 * @author Pluto
 * @since 2020-04-23 16:57:28
 */
@Slf4j
public class HttpRouteSocketPart extends SimpleSocketPart {

    private HttpRoute masterRoute = null;
    private LinkedHashMap<String, HttpRoute> routeMap = new LinkedHashMap<>();

    private ReentrantLock routeLock = new ReentrantLock();

    public HttpRouteSocketPart(IBelongControl belongThread) {
        super(belongThread);
    }

    /**
     * 因为socketPart是一对连接一次，为了减少计算量，进行预设值
     * 
     * @author Pluto
     * @since 2020-04-24 10:42:04
     * @param masterRoute
     * @param routeMap
     */
    public void presetRoute(HttpRoute masterRoute, LinkedHashMap<String, HttpRoute> routeMap) {
        routeLock.lock();
        this.masterRoute = masterRoute;
        this.routeMap = routeMap;
        routeLock.unlock();
    }

    /**
     * 选择路由并连接至目标
     * 
     * @author Pluto
     * @since 2020-04-24 11:01:24
     * @throws Exception
     */
    protected void routeHost() throws Exception {
        HttpRoute willConnect = null;

        InputStream inputStream = sendSocket.getInputStream();

        // 缓存数据，不能我们处理了就不给实际应用
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        // 临时输出列，用于读取一整行后进行字符串判断
        ByteArrayOutputStream tempOutput = new ByteArrayOutputStream();
        int flag = 0;
        for (;;) {
            // 依次读取
            int read = inputStream.read();
            output.write(read);
            tempOutput.write(read);

            if (read < 0) {
                break;
            }

            // 记录换行状态
            if ((char) read == '\r' || (char) read == '\n') {
                flag++;
            } else {
                flag = 0;
            }

            // 如果大于等于4则就表示http头结束了
            if (flag >= 4) {
                break;
            }

            // 等于2表示一行结束了，需要进行处理
            if (flag == 2) {
                // 将缓存中的数据进行字符串化，根据http标准，字符集为 ISO-8859-1
                String line = new String(tempOutput.toByteArray(), Charset.forName("ISO-8859-1"));

                // 重置临时输出流
                tempOutput = new ByteArrayOutputStream();

                if (StringUtils.startsWithIgnoreCase(line, "Host:")) {
                    String host = StringUtils.removeStartIgnoreCase(line, "Host:").trim();
                    host = StringUtils.split(host, ':')[0];

                    routeLock.lock();
                    try {
                        willConnect = routeMap.get(host);

                        if (Objects.isNull(willConnect)) {
                            willConnect = masterRoute;
                        }
                    } finally {
                        routeLock.unlock();
                    }
                    break;
                }
            }

        }

        if (Objects.isNull(willConnect)) {
            routeLock.lock();
            willConnect = masterRoute;
            routeLock.unlock();
        }

        InetSocketAddress destAddress = new InetSocketAddress(willConnect.getDestIp(), willConnect.getDestPort());
        recvSocket.connect(destAddress);

        OutputStream outputStream = recvSocket.getOutputStream();
        outputStream.write(output.toByteArray());
        // flush的原因，不排除这里全部读完了，导致缓存中没有数据，那及时创建了passway也不会主动flush而是挂在那里，防止遇到lazy的自动刷新特性
        outputStream.flush();
    }

    @Override
    public boolean createPassWay() {
        if (this.isAlive) {
            return true;
        }
        this.isAlive = true;
        try {
            routeHost();

            outToInPassway = new SimplePassway();
            outToInPassway.setBelongControl(this);
            outToInPassway.setSendSocket(sendSocket);
            outToInPassway.setRecvSocket(recvSocket);
            outToInPassway.setStreamCacheSize(getStreamCacheSize());

            inToOutPassway = new SimplePassway();
            inToOutPassway.setBelongControl(this);
            inToOutPassway.setSendSocket(recvSocket);
            inToOutPassway.setRecvSocket(sendSocket);
            inToOutPassway.setStreamCacheSize(getStreamCacheSize());

            outToInPassway.start();
            inToOutPassway.start();
        } catch (Exception e) {
            log.error("socketPart [" + this.socketPartKey + "] 隧道建立异常", e);
            this.stop();
            return false;
        }
        return true;
    }

}
