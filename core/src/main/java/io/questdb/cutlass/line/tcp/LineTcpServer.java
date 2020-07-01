package io.questdb.cutlass.line.tcp;

import java.io.Closeable;
import java.io.IOException;

import org.jetbrains.annotations.Nullable;

import io.questdb.MessageBus;
import io.questdb.WorkerPoolAwareConfiguration;
import io.questdb.WorkerPoolAwareConfiguration.ServerFactory;
import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.EagerThreadSetup;
import io.questdb.mp.SynchronizedJob;
import io.questdb.mp.WorkerPool;
import io.questdb.network.IOContextFactory;
import io.questdb.network.IODispatcher;
import io.questdb.network.IODispatchers;
import io.questdb.network.IORequestProcessor;
import io.questdb.std.Misc;
import io.questdb.std.ObjList;
import io.questdb.std.ThreadLocal;
import io.questdb.std.WeakObjectPool;

public class LineTcpServer implements Closeable {
    private static final Log LOG = LogFactory.getLog(LineTcpServer.class);

    @Nullable
    public static LineTcpServer create(
            CairoConfiguration cairoConfiguration,
            LineTcpReceiverConfiguration lineConfiguration,
            WorkerPool sharedWorkerPool,
            Log log,
            CairoEngine cairoEngine,
            MessageBus messageBus
    ) {
        if (!lineConfiguration.isEnabled()) {
            return null;
        }

        WorkerPool writerWorkerPool = WorkerPoolAwareConfiguration.configureWorkerPool(lineConfiguration.getWriterWorkerPoolConfiguration(), sharedWorkerPool);
        ServerFactory<LineTcpServer, WorkerPoolAwareConfiguration> factory = (netWorkerPoolConfiguration, engine, netWorkerPool, local,
                bus) -> new LineTcpServer(
                        cairoConfiguration,
                        lineConfiguration,
                        cairoEngine,
                        netWorkerPool,
                        writerWorkerPool,
                        bus);
        LineTcpServer server = WorkerPoolAwareConfiguration.create(lineConfiguration.getNetWorkerPoolConfiguration(), sharedWorkerPool, log, cairoEngine, factory, messageBus);
        if (writerWorkerPool != sharedWorkerPool) {
            writerWorkerPool.start(log);
        }
        return server;
    }

    private final IODispatcher<LineTcpConnectionContext> dispatcher;
    private final LineTcpConnectionContextFactory contextFactory;
    private final LineTcpMeasurementScheduler scheduler;
    private final ObjList<LineTcpConnectionContext> busyContexts = new ObjList<>();

    public LineTcpServer(
            CairoConfiguration cairoConfiguration,
            LineTcpReceiverConfiguration lineConfiguration,
            CairoEngine engine,
            WorkerPool netWorkerPool,
            WorkerPool writerWorkerPool,
            MessageBus messageBus
    ) {
        this.contextFactory = new LineTcpConnectionContextFactory(engine, lineConfiguration, messageBus, netWorkerPool.getWorkerCount());
        this.dispatcher = IODispatchers.create(
                lineConfiguration
                        .getNetDispatcherConfiguration(),
                contextFactory);
        netWorkerPool.assign(dispatcher);
        scheduler = new LineTcpMeasurementScheduler(cairoConfiguration, lineConfiguration, engine, writerWorkerPool);
        final IORequestProcessor<LineTcpConnectionContext> processor = (operation, context) -> {
            if (context.handleIO()) {
                busyContexts.add(context);
            }
        };
        netWorkerPool.assign(new SynchronizedJob() {
            @Override
            protected boolean runSerially() {
                int n = busyContexts.size();
                while (n > 0) {
                    n--;
                    if (!busyContexts.getQuick(n).handleIO()) {
                        busyContexts.remove(n);
                    }
                }
                return dispatcher.processIOQueue(processor);
            }
        });

        for (int i = 0, n = netWorkerPool.getWorkerCount(); i < n; i++) {
            // http context factory has thread local pools
            // therefore we need each thread to clean their thread locals individually
            netWorkerPool.assign(i, () -> {
                contextFactory.closeContextPool();
            });
        }
    }

    @Override
    public void close() {
        Misc.free(scheduler);
        Misc.free(contextFactory);
        Misc.free(dispatcher);
    }

    private class LineTcpConnectionContextFactory implements IOContextFactory<LineTcpConnectionContext>, Closeable, EagerThreadSetup {
        private final ThreadLocal<WeakObjectPool<LineTcpConnectionContext>> contextPool;
        private boolean closed = false;

        public LineTcpConnectionContextFactory(CairoEngine engine, LineTcpReceiverConfiguration configuration, @Nullable MessageBus messageBus, int workerCount) {
            this.contextPool = new ThreadLocal<>(
                    () -> new WeakObjectPool<>(() -> new LineTcpConnectionContext(configuration, scheduler, engine.getConfiguration().getMillisecondClock()),
                            configuration.getConnectionPoolInitialCapacity()));
        }

        @Override
        public void setup() {
            contextPool.get();
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }

        @Override
        public LineTcpConnectionContext newInstance(long fd, IODispatcher<LineTcpConnectionContext> dispatcher) {
            return contextPool.get().pop().of(fd, dispatcher);
        }

        @Override
        public void done(LineTcpConnectionContext context) {
            if (closed) {
                Misc.free(context);
            } else {
                context.of(-1, null);
                contextPool.get().push(context);
                LOG.info().$("pushed").$();
            }
        }

        private void closeContextPool() {
            Misc.free(this.contextPool.get());
            LOG.info().$("closed").$();
        }

    }
}
