### How does `FileChannel` and `AsynchronousFileChannel` read / write performance differ?

First, let's dive a bit into the implementation details of concrete subclasses of those abstract classes.

On Windows OS, `AsynchronousFileChannel.open(...)` returns `WindowsAsynchronousFileChannelImpl`.
When a write operation is issued into an asynchronous file channel, the following
method is called:

    <A> Future<Integer> implWrite(ByteBuffer src,
                                  long position,
                                  A attachment,
                                  CompletionHandler<Integer,? super A> handler) {
        ...

        // create Future and task to initiate write
        PendingFuture<Integer,A> result =
            new PendingFuture<Integer,A>(this, handler, attachment);
        
        WriteTask<A> writeTask = new WriteTask<A>(src, pos, rem, position, result);
        result.setContext(writeTask);

        // initiate I/O
        writeTask.run();
        return result;
    }
    
To see actual async IO logic, let's peek `writeTask :: run`

        ...
        // initiate the write
        n = writeFile(handle, address, rem, position, overlapped);
        
        if (n == IOStatus.UNAVAILABLE) {
            // I/O is pending
            return;
        } else {
            throw new InternalError("Unexpected result: " + n);
        }

`writeFile` is a native method, and since under normal execution flow
it must return pending status, it means there is some actual asynchronous 
disk access under the hood.

The interesting part comes here, `WindowsAsynchronousFileChannelImpl` behaviour
is very specific to Windows OS and in other platforms, e.g. MacOS, asynchronous file
channel is implemented in a way that asynchronous write operations
are actually performed in a synchronous fashion.

So if we follow same code digging path as above, but for MacOS:

* `AsynchronousFileChannel.open(...)` returns `SimpleAsynchronousFileChannelImpl`


    /**
    * "Portable" implementation of AsynchronousFileChannel for use on operating
    * systems that don't support asynchronous file I/O.
    */

    public class SimpleAsynchronousFileChannelImpl ...
        
        // lazy initialization of default thread pool for file I/O
        private static class DefaultExecutorHolder {
            static final ExecutorService defaultExecutor =
            ThreadPool.createDefault().executor();
        }

when calling `AsynchronousFileChannel.open(...)` either we pass an 
executor service or the channel creates its own cached thread pool, which are
used to issue blocking synchronous IOs. On `WindowsAsynchronousFileChannelImpl`
however, thread pool is used only for executing callbacks.

On `SimpleAsynchronousFileChannelImpl` main write logic is as follows:

    ...
    Runnable task = new Runnable () {
        // issue blocking IO operation
    }
    executor.execute(task);
    ...

Regarding non-asynchronous file channel, i.e. `FileChannel`, write operations are
always blocking and not even executor service is used.

As for the read operations, everything mentioned so far about writes, hold in 
exact same way for reads.

To explore more, visit these links, which are the source of code snippets above

* [openjdk : WindowsAsynchronousFileChannelImpl.java](https://github.com/openjdk/jdk/blob/master/src/java.base/windows/classes/sun/nio/ch/WindowsAsynchronousFileChannelImpl.java)
* [openjdk : SimpleAsynchronousFileChannelImpl.java](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/sun/nio/ch/SimpleAsynchronousFileChannelImpl.java)

Some benchmark results on Intel(R) Core(TM) i7-8550U CPU @ 1.80GHz 2.00 GHz, 8 GB RAM,
256 GB SSD, x64 Windows 10:

        WRITE: operationsCount - 50000, ioSegmentLen - 1000, syncIOThreadCount - 10, asyncIOThreadCount - 1. Runtime in MS: sync: 1297, async: 851
        READ: operationsCount - 50000, ioSegmentLen - 1000, syncIOThreadCount - 10, asyncIOThreadCount - 1. Runtime in MS: sync: 574, async: 512
        
        WRITE: operationsCount - 50000, ioSegmentLen - 1000, syncIOThreadCount - 20, asyncIOThreadCount - 1. Runtime in MS: sync: 1272, async: 834
        READ: operationsCount - 50000, ioSegmentLen - 1000, syncIOThreadCount - 20, asyncIOThreadCount - 1. Runtime in MS: sync: 538, async: 427
        
        WRITE: operationsCount - 50000, ioSegmentLen - 1000, syncIOThreadCount - 30, asyncIOThreadCount - 1. Runtime in MS: sync: 1327, async: 901
        READ: operationsCount - 50000, ioSegmentLen - 1000, syncIOThreadCount - 30, asyncIOThreadCount - 1. Runtime in MS: sync: 517, async: 423
        
        WRITE: operationsCount - 50000, ioSegmentLen - 1000, syncIOThreadCount - 40, asyncIOThreadCount - 1. Runtime in MS: sync: 2272, async: 1219
        READ: operationsCount - 50000, ioSegmentLen - 1000, syncIOThreadCount - 40, asyncIOThreadCount - 1. Runtime in MS: sync: 523, async: 404
        
        WRITE: operationsCount - 50000, ioSegmentLen - 1000, syncIOThreadCount - 50, asyncIOThreadCount - 1. Runtime in MS: sync: 1704, async: 787
        READ: operationsCount - 50000, ioSegmentLen - 1000, syncIOThreadCount - 50, asyncIOThreadCount - 1. Runtime in MS: sync: 516, async: 413
        
        WRITE: operationsCount - 150000, ioSegmentLen - 1000, syncIOThreadCount - 10, asyncIOThreadCount - 1. Runtime in MS: sync: 4227, async: 2608
        READ: operationsCount - 150000, ioSegmentLen - 1000, syncIOThreadCount - 10, asyncIOThreadCount - 1. Runtime in MS: sync: 1589, async: 1283
        
        WRITE: operationsCount - 150000, ioSegmentLen - 1000, syncIOThreadCount - 20, asyncIOThreadCount - 1. Runtime in MS: sync: 4562, async: 2481
        READ: operationsCount - 150000, ioSegmentLen - 1000, syncIOThreadCount - 20, asyncIOThreadCount - 1. Runtime in MS: sync: 1535, async: 1273
        
        WRITE: operationsCount - 150000, ioSegmentLen - 1000, syncIOThreadCount - 30, asyncIOThreadCount - 1. Runtime in MS: sync: 4294, async: 2098
        READ: operationsCount - 150000, ioSegmentLen - 1000, syncIOThreadCount - 30, asyncIOThreadCount - 1. Runtime in MS: sync: 1559, async: 1257
        
        WRITE: operationsCount - 150000, ioSegmentLen - 1000, syncIOThreadCount - 40, asyncIOThreadCount - 1. Runtime in MS: sync: 3610, async: 1959
        READ: operationsCount - 150000, ioSegmentLen - 1000, syncIOThreadCount - 40, asyncIOThreadCount - 1. Runtime in MS: sync: 1578, async: 1269
        
        WRITE: operationsCount - 150000, ioSegmentLen - 1000, syncIOThreadCount - 50, asyncIOThreadCount - 1. Runtime in MS: sync: 4030, async: 2003
        READ: operationsCount - 150000, ioSegmentLen - 1000, syncIOThreadCount - 50, asyncIOThreadCount - 1. Runtime in MS: sync: 1618, async: 1291

Some on MacOS:

    WRITE: operationsCount - 50000, ioSegmentLen - 10000, syncIOThreadCount - 10, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 3228, async: 2821
    READ: operationsCount - 50000, ioSegmentLen - 10000, syncIOThreadCount - 10, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 98, async: 180
    
    WRITE: operationsCount - 50000, ioSegmentLen - 10000, syncIOThreadCount - 20, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 4209, async: 2592
    READ: operationsCount - 50000, ioSegmentLen - 10000, syncIOThreadCount - 20, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 85, async: 171
    
    WRITE: operationsCount - 50000, ioSegmentLen - 10000, syncIOThreadCount - 30, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 4402, async: 2649
    READ: operationsCount - 50000, ioSegmentLen - 10000, syncIOThreadCount - 30, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 94, async: 185
    
    WRITE: operationsCount - 50000, ioSegmentLen - 10000, syncIOThreadCount - 40, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 5073, async: 3046
    READ: operationsCount - 50000, ioSegmentLen - 10000, syncIOThreadCount - 40, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 93, async: 257
    
    WRITE: operationsCount - 50000, ioSegmentLen - 10000, syncIOThreadCount - 50, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 5426, async: 3446
    READ: operationsCount - 50000, ioSegmentLen - 10000, syncIOThreadCount - 50, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 98, async: 181
    
    WRITE: operationsCount - 150000, ioSegmentLen - 10000, syncIOThreadCount - 10, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 25517, async: 25926
    READ: operationsCount - 150000, ioSegmentLen - 10000, syncIOThreadCount - 10, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 1121, async: 801
    
    WRITE: operationsCount - 150000, ioSegmentLen - 10000, syncIOThreadCount - 20, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 42857, async: 10448
    READ: operationsCount - 150000, ioSegmentLen - 10000, syncIOThreadCount - 20, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 298, async: 565
    
    WRITE: operationsCount - 150000, ioSegmentLen - 10000, syncIOThreadCount - 30, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 46521, async: 33954
    READ: operationsCount - 150000, ioSegmentLen - 10000, syncIOThreadCount - 30, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 1305, async: 22172
    
    WRITE: operationsCount - 150000, ioSegmentLen - 10000, syncIOThreadCount - 40, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 43643, async: 15356
    READ: operationsCount - 150000, ioSegmentLen - 10000, syncIOThreadCount - 40, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 277, async: 548
    
    WRITE: operationsCount - 150000, ioSegmentLen - 10000, syncIOThreadCount - 50, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 44054, async: 25371
    READ: operationsCount - 150000, ioSegmentLen - 10000, syncIOThreadCount - 50, asyncIOThreadCount - 1. Runtime in MS: 	 sync: 826, async: 2156