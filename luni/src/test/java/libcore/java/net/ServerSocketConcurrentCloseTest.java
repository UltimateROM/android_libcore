package libcore.java.net;

import junit.framework.TestCase;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketImpl;
import java.net.SocketException;
import java.net.SocketAddress;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for race conditions between {@link ServerSocket#close()} and
 * {@link ServerSocket#accept()}.
 */
public class ServerSocketConcurrentCloseTest extends TestCase {
    private static final String TAG = ServerSocketConcurrentCloseTest.class.getSimpleName();

    /**
     * The implementation of {@link ServerSocket#accept()} checks closed state before
     * delegating to the {@link ServerSocket#implAccept(Socket)}, however this is not
     * sufficient for correctness because the socket might be closed after the check.
     * This checks that implAccept() itself also detects closed sockets and throws
     * SocketException.
     */
    public void testImplAccept_detectsClosedState() throws Exception {
        /** A ServerSocket that exposes implAccept() */
        class ExposedServerSocket extends ServerSocket {
            public ExposedServerSocket() throws IOException {
                super(0 /* allocate port number automatically */);
            }

            public void implAcceptExposedForTest(Socket socket) throws IOException {
                implAccept(socket);
            }
        }
        final ExposedServerSocket serverSocket = new ExposedServerSocket();
        serverSocket.close();
        // implAccept() on background thread to prevent this test hanging
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch threadFinishedLatch = new CountDownLatch(1);
        Thread thread = new Thread("implAccept() closed ServerSocket") {
            public void run() {
                try {
                    // Hack: Need to subclass to access the protected constructor without reflection
                    Socket socket = new Socket((SocketImpl) null) { };
                    serverSocket.implAcceptExposedForTest(socket);
                } catch (SocketException expected) {
                    // pass
                } catch (IOException|RuntimeException e) {
                    failure.set(e);
                } finally {
                    threadFinishedLatch.countDown();
                }
            }
        };
        thread.start();

        boolean completed = threadFinishedLatch.await(5, TimeUnit.SECONDS);
        assertTrue("implAccept didn't throw or return within time limit", completed);
        Exception e = failure.get();
        if (e != null) {
            throw new AssertionError("Unexpected exception", e);
        }
        thread.join();
    }

    /**
     * Test for b/27763633.
     */
    public void testConcurrentServerSocketCloseReliablyThrows() {
        int numIterations = 100;
        for (int i = 0; i < numIterations; i++) {
            checkConnectIterationAndCloseSocket("Iteration " + (i+1) + " of " + numIterations,
                    /* sleepMsec */ 50, /* maxSleepsPerIteration */ 3);
        }
    }

    /**
     * Checks that a concurrent {@link ServerSocket#close()} reliably causes
     * {@link ServerSocket#accept()} to throw {@link SocketException}.
     *
     * <p>Spawns a server and client thread that continuously connect to each
     * other for up to {@code maxSleepsPerIteration * sleepMsec} msec.
     * Then, closes the {@link ServerSocket} and verifies that the server
     * quickly shuts down.
     */
    private void checkConnectIterationAndCloseSocket(String iterationName,
        int sleepMsec, int maxSleepsPerIteration) {
        ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket(0 /* allocate port number automatically */);
        } catch (IOException e) {
            fail("Abort: " + e);
            throw new AssertionError("unreachable");
        }
        ServerRunnable serverRunnable = new ServerRunnable(serverSocket);
        Thread serverThread = new Thread(serverRunnable, TAG + " (server)");
        ClientRunnable clientRunnable = new ClientRunnable(
                serverSocket.getLocalSocketAddress(), serverRunnable);
        Thread clientThread = new Thread(clientRunnable, TAG + " (client)");
        serverThread.start();
        clientThread.start();
        try {
            assertTrue("Slow server startup", serverRunnable.awaitStart(1, TimeUnit.SECONDS));
            assertTrue("Slow client startup", clientRunnable.awaitStart(1, TimeUnit.SECONDS));
            if (serverRunnable.isShutdown()) {
                fail("Server prematurely shut down");
            }
            // Let server and client keep connecting for some time, then close the socket.
            for (int i = 0; i < maxSleepsPerIteration; i++) {
              Thread.sleep(sleepMsec);
              if (serverRunnable.numSuccessfulConnections > 0) {
                break;
              }
            }
            try {
                serverSocket.close();
            } catch (IOException e) {
                throw new AssertionError("serverSocket.close() failed: ", e);
            }
            // Check that the server shut down quickly in response to the socket closing.
            long hardLimitSeconds = 5;
            boolean serverShutdownReached = serverRunnable.awaitShutdown(hardLimitSeconds, TimeUnit.SECONDS);
            if (!serverShutdownReached) { // b/27763633
                String serverStackTrace = stackTraceAsString(serverThread.getStackTrace());
                fail("Server took > " + hardLimitSeconds + "sec to react to serverSocket.close(). "
                        + "Server thread's stackTrace: " + serverStackTrace);
            }
            // Guard against the test passing as a false positive if no connections were actually
            // established. If the test was running for much longer then this would fail during
            // later iterations because TCP connections cannot be closed immediately (they stay
            // in TIME_WAIT state for a few minutes) and only some number (tens of thousands?)
            // can be open at a time. If this assertion turns out flaky in future, consider
            // reducing the iteration time or number of iterations.
            assertTrue(String.format(Locale.US, "%s: No connections in %d msec.",
                    iterationName, maxSleepsPerIteration * sleepMsec),
                    serverRunnable.numSuccessfulConnections > 0);

            assertTrue(serverRunnable.isShutdown());
            // Sanity check to ensure the threads don't live into the next iteration. This should
            // be quick because we only get here if shutdownLatch reached 0 within the time limit.
            serverThread.join();
            clientThread.join();
        } catch (InterruptedException e) {
            throw new AssertionError("Unexpected interruption", e);
        }
    }

    /**
     * Repeatedly tries to connect to and disconnect from a SocketAddress until
     * it observes {@code shutdownLatch} reaching 0. Does not read/write any
     * data from/to the socket.
     */
    static class ClientRunnable implements Runnable {
        private final SocketAddress socketAddress;

        private final ServerRunnable serverRunnable;
        private final CountDownLatch startLatch = new CountDownLatch(1);

        public ClientRunnable(
                SocketAddress socketAddress, ServerRunnable serverRunnable) {
            this.socketAddress = socketAddress;
            this.serverRunnable = serverRunnable;
        }

        @Override
        public void run() {
            startLatch.countDown();
            while (!serverRunnable.isShutdown()) {
                try {
                    Socket socket = new Socket();
                    socket.connect(socketAddress, /* timeout (msec) */ 10);
                    socket.close();
                } catch (IOException e) {
                    // harmless, as long as enough connections are successful
                }
            }
        }

        public boolean awaitStart(long timeout, TimeUnit timeUnit) throws InterruptedException {
            return startLatch.await(timeout, timeUnit);
        }

    }

    /**
     * Repeatedly accepts connections from a ServerSocket and immediately closes them.
     * When it encounters a SocketException, it counts down the CountDownLatch and exits.
     */
    static class ServerRunnable implements Runnable {
        private final ServerSocket serverSocket;
        volatile int numSuccessfulConnections;
        private final CountDownLatch startLatch = new CountDownLatch(1);
        private final CountDownLatch shutdownLatch = new CountDownLatch(1);

        ServerRunnable(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
        }

        @Override
        public void run() {
            startLatch.countDown();
            int numSuccessfulConnections = 0;
            while (true) {
                try {
                    Socket socket = serverSocket.accept();
                    numSuccessfulConnections++;
                    socket.close();
                } catch (SocketException e) {
                    this.numSuccessfulConnections = numSuccessfulConnections;
                    shutdownLatch.countDown();
                    return;
                } catch (IOException e) {
                    // harmless, as long as enough connections are successful
                }
            }
        }

        public boolean awaitStart(long timeout, TimeUnit timeUnit) throws InterruptedException {
            return startLatch.await(timeout, timeUnit);
        }

        public boolean awaitShutdown(long timeout, TimeUnit timeUnit) throws InterruptedException {
            return shutdownLatch.await(timeout, timeUnit);
        }

        public boolean isShutdown() {
            return shutdownLatch.getCount() == 0;
        }
    }

    private static String stackTraceAsString(StackTraceElement[] stackTraceElements) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement stackTraceElement : stackTraceElements) {
            sb.append("\n\t at ").append(stackTraceElement);
        }
        return sb.toString();
    }

}
