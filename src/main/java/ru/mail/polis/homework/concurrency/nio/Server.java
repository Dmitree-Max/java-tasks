package ru.mail.polis.homework.concurrency.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Слушает несколько портов. Принимает список операций, производит с ними вычисления
 * и отправляет результат на указанный порт. Для каждого клиента свой уникальный порт.
 * Чтение из всех сокетов должно происходить в один поток.
 * Отправка результата тоже в один поток, отличный от потока чтения.
 *
 * Вычисления должны производиться в отдельном экзекьюторе.
 *
 * Сервер умеет принимать сигналы о закрытии запроса (должен перестать принимать/вычислять и отправлять что либо
 * по заданному запросу) и о закрытии клиента (должен завершить все вычисления от клиента и закрыть клиентский сокет).
 */
public class Server {
    private static final int RESULT_RESPONSE             = 0;
    private static final int OPERATION_RECEIVED_RESPONSE = 1;
    private static final int REQUEST_CANCELED_RESPONSE   = 2;

    // all messages start with clientId and message type code
    private static final int OPERATION_HEADER_MESSAGE = 0;
    private static final int OPERAND_MESSAGE          = 1;
    private static final int CLOSE_CLIENT_MESSAGE     = 2;
    private static final int CANSEL_REQUEST_MESSAGE   = 3;
    private static final int REGISTER_CLIENT_MESSAGE  = 4;

    private final Executor calculateExecutor;
    class ClientInfo {
        Map<Integer, ServerOperation> operations;
        final Integer port;
        SocketChannel chanel;

        ClientInfo(Integer port) {
            this.port = port;
            this.operations = new HashMap<>();
            try {
                chanel = SocketChannel.open(new InetSocketAddress("localhost", port));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    private final Map<Integer, ClientInfo> clients = new HashMap<>();

    public Server(int[] serverPorts, int calculateThreadsCount) throws IOException {
        calculateExecutor = Executors.newFixedThreadPool(calculateThreadsCount);
        new Thread(() -> {
            try (Selector selector = Selector.open()) {
                try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {
                    for (int serverPort : serverPorts) {
                        serverSocket.bind(new InetSocketAddress("localhost", serverPort));
                    }
                    serverSocket.configureBlocking(false);
                    serverSocket.register(selector, SelectionKey.OP_ACCEPT);
                    ByteBuffer buffer = ByteBuffer.allocate(256);
                    while (true) {
                        selector.select();
                        Set<SelectionKey> selectedKeys = selector.selectedKeys();
                        Iterator<SelectionKey> iterator = selectedKeys.iterator();
                        while (iterator.hasNext()) {

                            SelectionKey key = iterator.next();

                            if (key.isAcceptable()) {
                                register(selector, serverSocket);
                            }

                            if (key.isReadable()) {
                                processClientRequest(buffer, key);
                            }
                            iterator.remove();
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Можно редактировать метод. Должен вернуть всех клиентов
     * У меня с каждым клиентом ассоциирован id. Возвращаются все id клиентов.
     * Закртый клиент не считается клиентом.
     */
    public Integer[] getClients() {
        return (Integer[]) clients.keySet().toArray();
    }

    /**
     * Можно редактировать метод. Должен вернуть все операции для заданного клиента.
     * Если клиента нет или он уже закрыт -- вернуть null. Если клиент есть, но операций нет -- вернуть пустой список.
     */
    public List<ServerOperation> getOperationsForClient(Integer clientId) {
        if (clients.containsKey(clientId)) {
            return new ArrayList<>(clients.get(clientId).operations.values());
        }
        return null;
    }

    private static void register(Selector selector, ServerSocketChannel serverSocket) throws IOException {
        SocketChannel client = serverSocket.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
    }

    private void processClientRequest(ByteBuffer buffer, SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        synchronized (channel) {
            channel.read(buffer);
        }
        Integer clientId = buffer.getInt();
        if (!clients.containsKey(clientId) && buffer.getInt() == REGISTER_CLIENT_MESSAGE) {
            Integer responsePort = buffer.getInt();
            clients.put(clientId, new ClientInfo(responsePort));
            buffer.clear();
            return;
        }

        Integer messageType = buffer.getInt();
        switch (messageType) {
            case OPERATION_HEADER_MESSAGE -> {
                Integer operationId = buffer.getInt();
                Integer operandsAmount = buffer.getInt();
                clients.get(clientId).operations.put(operationId, new ServerOperation(operandsAmount, operationId));
                break;
            }
            case CANSEL_REQUEST_MESSAGE -> {
                Integer operationId  = buffer.getInt();
                ServerOperation operation = clients.get(clientId).operations.getOrDefault(operationId, null);
                if (operation != null) {
                    synchronized (operation) {
                        if (operation.state != ServerState.CLOSE || operation.state != ServerState.DONE) {
                            operation.state = ServerState.CANCEL;
                            sendRequestCanselledResponse(clients.get(clientId).chanel);
                        }
                    }
                }
                break;
            }
            case OPERAND_MESSAGE -> {
                Integer operationId  = buffer.getInt();
                Integer operandOrder = buffer.getInt();
                Operand operand = new Operand(buffer);
                ServerOperation operation = clients.get(clientId).operations.getOrDefault(operationId, null);
                if (operation != null) {
                    operation.AddOperand(operand, operandOrder);
                    synchronized (operation) {
                        if (operation.state == ServerState.WAITING_CALCULATE) {
                            sendOperationFullRecievedResponse(clients.get(clientId).chanel);
                            startCalculation(operation);
                        }
                    }
                }
                break;
            }
            case CLOSE_CLIENT_MESSAGE -> {
                for (ServerOperation operation : clients.get(clientId).operations.values()) {
                    synchronized (operation) {
                        if (operation.state != ServerState.CLOSE || operation.state != ServerState.DONE) {
                            operation.state = ServerState.CLOSE;
                        }
                    }
                }
                synchronized (clients.get(clientId)) {
                    clients.get(clientId).chanel.close();
                }
                sendClientClosedResponse(clients.get(clientId).chanel);
            }
        }
        buffer.clear();
    }
}
