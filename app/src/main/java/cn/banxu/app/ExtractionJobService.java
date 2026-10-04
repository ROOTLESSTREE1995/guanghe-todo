package cn.banxu.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ExtractionJobService extends JobService {
    private static final int JOB_ID = 7216;
    private static volatile boolean active;
    private volatile int generation;
    static synchronized void schedule(Context c) {
        if (!Store.get(c).hasPending() || active) return;
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        long delay = Math.max(0, Store.get(c).nextPendingAt() - System.currentTimeMillis());
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, ExtractionJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(delay)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) Store.get(c).setMeta("lastError", "系统暂未接受后台任务，请稍后点击重新整理");
    }
    @Override public boolean onStartJob(JobParameters params) {
        final int ownGeneration = ++generation; active = true;
        BanxuApp.NETWORK.execute(() -> {
            Store store = Store.get(this);
            try {
                // Bounded batches avoid monopolizing background execution.
                for (int count = 0; count < 12 && generation == ownGeneration; count++) {
                    SecureSettings settings = new SecureSettings(this); JSONObject config = settings.publicJson();
                    if (!config.optBoolean("cloudEnabled") || !config.optBoolean("hasApiKey")) { Repository.process(this); break; }
                    JSONObject raw = store.claim(); if (raw == null) break; BanxuApp.changed();
                    // A source deselected after capture must not be uploaded from the persisted queue.
                    if (!Repository.sourceAllowed(config, raw)) {
                        store.setInboxStatus(raw.getString("id"), "ignored", "来源已取消选择，未上传", false, 0);
                        BanxuApp.changed(); continue;
                    }
                    try {
                        JSONArray candidates = DeepSeekClient.extract(settings.apiKey(), config.optString("model"), raw);
                        JSONArray extracted = new JSONArray();
                        if (candidates.length() == 0 && "manual".equals(raw.optString("packageName"))) {
                            candidates.put(new JSONObject().put("kind", "task").put("title", DomainRules.clipped(raw.optString("text").replace('\n', ' '), 70))
                                .put("student", "").put("detail", "手动录入已保存为待办。").put("dueAt", 0).put("confidence", 0.0)
                                .put("dueText", "").put("timePrecision", "unknown").put("timeNote", "未确定时间，暂不设到点提醒。"));
                        }
                        for (int i = 0; i < candidates.length(); i++) extracted.put(Repository.extractedItem(candidates.getJSONObject(i), raw, config.optInt("leadMinutes", 15)));
                        Repository.finishExtraction(this, raw.getString("id"), extracted);
                    } catch (DeepSeekClient.ApiException ex) { store.failExtraction(raw.getString("id"), ex.getMessage(), ex.retryable); }
                    catch (Exception ex) { store.failExtraction(raw.getString("id"), "整理失败，请检查密钥或手动重试", false); }
                    BanxuApp.changed();
                }
            } catch (Exception ex) { store.setMeta("lastError", "后台整理中断，消息已保留，可重新整理"); }
            finally {
                active = false; BanxuApp.changed();
                if (generation == ownGeneration) jobFinished(params, store.hasPending());
            }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { generation++; active = false; return true; }
}
