package org.ss.govern.server.node.slave;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ss.govern.server.node.NetworkManager;
import org.ss.govern.server.node.NodeStatus;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * master节点间网络连接写线程
 * 每两个节点之间启动一个写线程
 * @author wangsz
 * @create 2020-04-11
 **/
public class SlaveNetworkWriteThread extends Thread {

    private static final Logger LOG = LoggerFactory.getLogger(SlaveNetworkWriteThread.class);

    /**
     * master节点间的网络连接
     */
    private Socket socket;

    private LinkedBlockingQueue<ByteBuffer> queueSend;

    private SlaveNetworkManager manager;

    DataOutputStream dout = null;

    public SlaveNetworkWriteThread(Socket socket,
                                   LinkedBlockingQueue<ByteBuffer> queueSend,
                                   SlaveNetworkManager masterNetworkManager) {
        this.manager = masterNetworkManager;
        this.queueSend = queueSend;
        this.socket = socket;
        try {
            dout = new DataOutputStream(socket.getOutputStream());
        } catch (IOException e) {
            LOG.error("Unable to access socket output stream", e);
            manager.closeSocket(socket);
            NodeStatus.fatal();
        }
    }

    private synchronized void finish() {
        LOG.debug("Calling finish for SlaveNetworkWriteThread");
        if(!NodeStatus.isRunning()){
            return;
        }
        NodeStatus.fatal();
        manager.closeSocket(socket);
        this.interrupt();
    }

    @Override
    public void run() {
        LOG.info("start a write IO thread for remote node:" + socket.getRemoteSocketAddress());
        while (NodeStatus.isRunning()) {
            try {
                ByteBuffer message = queueSend.take();
                dout.writeInt(message.capacity());
                dout.write(message.array());
                dout.flush();
                if(LOG.isDebugEnabled()) {
                    LOG.debug("send message to node :" + socket.getRemoteSocketAddress()
                            + ", message size is " + message.capacity());
                }
            } catch (InterruptedException e) {
                LOG.warn("Interrupted while waiting for message on queue", e);
            } catch (IOException e) {
                LOG.error("send data to remote node error", e);
                finish();
            }
        }

    }
}
