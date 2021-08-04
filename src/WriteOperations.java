import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class WriteOperations {

    private static final String DEFAULT_FILE = "file.txt";

    private final int operationsCount;

    private final int syncIOThreadCount;
    private final int asyncIOThreadCount;

    private final List<ByteBuffer> buffers;
    private final List<Integer> indices;

    public WriteOperations(int operationsCount, int ioSegmentLen, int syncIOThreadCount, int asyncIOThreadCount) {
        this.operationsCount = operationsCount;
        this.syncIOThreadCount = syncIOThreadCount;
        this.asyncIOThreadCount = asyncIOThreadCount;

        indices = IntStream.range(0, operationsCount).boxed().collect(Collectors.toList());
        Collections.shuffle(indices);

        buffers = new ArrayList<>(operationsCount);
        for (int i = 0; i < operationsCount; ++i) {
            buffers.add(ByteBuffer.wrap(
                    ("-".repeat(ioSegmentLen) + System.lineSeparator()).getBytes()
            ));
        }
    }

    public long[] invoke() {
        try {
            return new long[] {
                    fileChannelWritePerf(),
                    asyncFileChannelWritePerf()
            };
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void resetBuffers() {
        for (ByteBuffer buf : buffers) {
            buf.position(0);
        }
    }

    private long fileChannelWritePerf() throws IOException, InterruptedException {
        resetBuffers();
        Path filePath = IOUtil.createFile(DEFAULT_FILE, false);
        FileChannel chan = FileChannel.open(filePath, Set.of(StandardOpenOption.WRITE));

        List<Runnable> runnables = new ArrayList<>(operationsCount);
        for (int i : indices) {
            runnables.add(() -> {
                try {
                    chan.write(buffers.get(i), ((long) i) * buffers.get(i).capacity());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(syncIOThreadCount);

        long now = System.currentTimeMillis();
        for (Runnable r : runnables) {
            executor.submit(r);
        }

        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
            throw new IllegalStateException("operations did not complete in 10 minutes");
        }

        return System.currentTimeMillis() - now;
    }

    private long asyncFileChannelWritePerf() throws IOException, InterruptedException {
        resetBuffers();
        Path filePath = IOUtil.createFile(DEFAULT_FILE, false);

        ExecutorService executor = Executors.newFixedThreadPool(asyncIOThreadCount);

        AsynchronousFileChannel asyncChan = AsynchronousFileChannel
                .open(filePath, Set.of(StandardOpenOption.WRITE), executor);

        long now = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(operationsCount);
        for (int i : indices) {
            asyncChan.write(buffers.get(i),
                    ((long) i) * buffers.get(i).capacity(), null, new CompletionHandler<Integer, Object>() {
                        @Override
                        public void completed(Integer result, Object attachment) {
                            latch.countDown();
                        }

                        @Override
                        public void failed(Throwable exc, Object attachment) {
                            throw new RuntimeException(exc);
                        }
                    });
        }
        latch.await();
        long passed = System.currentTimeMillis() - now;
        executor.shutdown();
        return passed;
    }
}
