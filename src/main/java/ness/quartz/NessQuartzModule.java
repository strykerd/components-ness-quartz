package ness.quartz;

import java.util.Iterator;
import java.util.Properties;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationConverter;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.Duration;
import org.quartz.Job;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.spi.JobFactory;
import org.skife.config.TimeSpan;

import com.google.common.base.Preconditions;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.nesscomputing.config.Config;
import com.nesscomputing.config.ConfigProvider;
import com.nesscomputing.logging.Log;

/**
 * Guice Module to integrate the Quartz Job scheduler. Using this module
 * allows usage of Quartz without the TrumpetJobManagerModule().
 */
public final class NessQuartzModule extends AbstractModule
{
    public static final Log LOG = Log.findLog();

    public static final String NESS_JOB_NAME = "ness.job";
    public static final Named NESS_JOB_NAMED = Names.named(NESS_JOB_NAME);

    private final Config config;

    public NessQuartzModule(final Config config)
    {
        this.config = config;
    }

    @Override
    public void configure()
    {
        bind(JobFactory.class).to(GuiceJobFactory.class).in(Scopes.SINGLETON);

        // Must be eager, otherwise the service is never started!
        bind(Scheduler.class).toProvider(SchedulerProvider.class).asEagerSingleton();
        bind(QuartzJmxTrigger.class).asEagerSingleton();

        final Configuration nessJobConfig = config.getConfiguration(NESS_JOB_NAME);
        bind(Configuration.class).annotatedWith(NESS_JOB_NAMED).toInstance(nessJobConfig);
        bind(NessQuartzConfig.class).toProvider(ConfigProvider.of(NessQuartzConfig.class)).in(Scopes.SINGLETON);

        configureJobs(nessJobConfig);
    }

    @Provides
    @Singleton
    SchedulerFactory getSchedulerFactory(final Config config)
        throws SchedulerException
    {
        final Properties quartzProperties = ConfigurationConverter.getProperties(config.getConfiguration());
        final SchedulerFactory factory = new StdSchedulerFactory(quartzProperties);
        return factory;
    }

    private void configureJobs(final Configuration jobConfig)
    {
        for (Iterator<?> it = jobConfig.getKeys(); it.hasNext(); ) {
            final String key = it.next().toString();
            final String [] keys = StringUtils.split(key, ".");
            if (keys.length != 2) {
                LOG.warn("Ignore invalid key %s", key);
                continue;
            }
            if ("class".equals(keys[1])) {
                configureJob(keys[0], jobConfig.subset(keys[0]));
            }
        }
    }

    private void configureJob(final String jobName, final Configuration jobConfig) {
        try {
            final String className = jobConfig.getString("class");
            Preconditions.checkState(className != null, "No class key found (but it existed earlier!");
            final Class<? extends Job> jobClass = Class.forName(className).asSubclass(Job.class);

            // Bind the class. if it needs to be a singleton, annotate accordingly. Do not add
            // in(Scopes.SINGLETON) here, because then it is not possible to undo that.
            bind(jobClass);

            final QuartzJobBinder binder = QuartzJobBinder.bindQuartzJob(binder(), jobClass);
            binder.name(jobName);

            if (jobConfig.containsKey("delay")) {
                binder.delay(parseDuration(jobConfig, "delay"));
            }

            if (jobConfig.containsKey("repeat")) {
                binder.repeat(parseDuration(jobConfig, "repeat"));
            }

            if (jobConfig.containsKey("group")) {
                binder.group(jobConfig.getString("group"));
            }

            if (jobConfig.containsKey("enabled")) {
                binder.enabled(jobConfig.getBoolean("enabled"));
            }
            else {
                LOG.warn("Found job %s but no key for enabling!", jobName);
            }

            LOG.info("Registered %s", binder);
            binder.register();
        }
        catch (ClassCastException cce) {
            addError(cce);
        }
        catch (ClassNotFoundException cnfe) {
            addError(cnfe);
        }
    }

    private static Duration parseDuration(final Configuration config, final String key)
    {
        final TimeSpan timeSpan = new TimeSpan(config.getString(key));
        return new Duration(timeSpan.getMillis());
    }

}