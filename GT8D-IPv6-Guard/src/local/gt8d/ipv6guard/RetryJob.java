package local.gt8d.ipv6guard;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class RetryJob extends JobService {
    @Override public boolean onStartJob(JobParameters parameters) {
        if (!Guard.enabled(this)) return false;
        new Thread(() -> {
            boolean retry = false;
            try { Guard.apply(this, "job " + parameters.getJobId()); }
            catch (Exception e) { retry = true; Guard.log(this, "job attempt: " + e); }
            jobFinished(parameters, retry && Guard.enabled(this));
        }, "guard-job").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) { return Guard.enabled(this); }
}
