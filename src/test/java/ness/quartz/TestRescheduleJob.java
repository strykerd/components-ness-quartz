package ness.quartz;

import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;

import ness.quartz.internal.TestingQuartzModule;

import org.joda.time.Duration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Scopes;
import com.nesscomputing.config.Config;
import com.nesscomputing.lifecycle.Lifecycle;
import com.nesscomputing.lifecycle.LifecycleStage;
import com.nesscomputing.lifecycle.guice.LifecycleModule;

public class TestRescheduleJob
{
    private Injector injector = null;

    @Before
    public void setup()
    {
        final Config config = Config.getConfig(URI.create("classpath:/test-config"), "quartz");

        injector = Guice.createInjector(
                        new NessQuartzModule(config),
                        new LifecycleModule(),
                        new TestingQuartzModule(config),
                        new AbstractModule() {
                            @Override
                            public void configure() {
                                bind(Counter.class).in(Scopes.SINGLETON);
                                bind(CounterJob.class);
                            }
                        }
        );
    }

    @After
    public void teardown()
    {
        Assert.assertNotNull(injector);
        injector = null;
    }

    @Test
    public void testAdHoc() throws Exception
    {
        final Scheduler scheduler = injector.getInstance(Scheduler.class);
        Assert.assertNotNull(scheduler);

        final Lifecycle lifecycle = injector.getInstance(Lifecycle.class);

        Assert.assertFalse(scheduler.isStarted());
        lifecycle.executeTo(LifecycleStage.START_STAGE);

        Thread.sleep(100L);
        Assert.assertTrue(scheduler.isStarted());

        AdHocQuartzJob.forClass(CounterJob.class).delay(Duration.standardSeconds(1)).name("ad-hoc").submit(scheduler);

        Thread.sleep(2000L);

        Counter counter = injector.getInstance(Counter.class);

        Assert.assertEquals(2L, counter.getCount());

        Assert.assertFalse(scheduler.isShutdown());
        lifecycle.executeTo(LifecycleStage.STOP_STAGE);
        Assert.assertTrue(scheduler.isShutdown());
    }

    public static class Counter
    {
        private final AtomicLong count = new AtomicLong(0L);

        public void increment()
        {
            count.incrementAndGet();
        }

        public long getCount()
        {
            return count.get();
        }
    }

    public static class CounterJob implements Job
    {
        private final Counter counter;
        private final Scheduler scheduler;

        @Inject
        public CounterJob(final Counter counter, final Scheduler scheduler)
        {
            this.counter = counter;
            this.scheduler = scheduler;
        }

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException
        {
            counter.increment();
            if (counter.getCount() < 2) {
                try {
                    RescheduledQuartzJob.forTrigger(context.getTrigger()).submit(scheduler);
                }
                catch (SchedulerException se) {
                    Assert.fail(se.getMessage());
                }
            }
        }
    }
}