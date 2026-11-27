<?php

namespace App\Jobs;

use Illuminate\Bus\Queueable;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Bus\Dispatchable;
use Illuminate\Queue\InteractsWithQueue;
use Illuminate\Queue\SerializesModels;
use Illuminate\Support\Facades\Log;

/**
 * STUB — NOT IMPLEMENTED until M5/M6.
 *
 * This job will drive the APK validation & security pipeline
 * (docs/03-pipelines-milestones-risks.md §F): quarantine → SHA-256 →
 * manifest inspection → malware scan → static analysis → risk score →
 * auto-approve or manual review. The Python workers that do the real
 * analysis do not exist yet.
 *
 * Until M5/M6 this job FAILS LOUDLY on purpose: it logs a warning and
 * throws, with $tries = 1 so it lands in `failed_jobs` instead of
 * retrying forever. It must NEVER silently succeed — a silent success
 * would look like a scanned APK. The submission stays in quarantine
 * (fail-closed) until the real pipeline exists.
 */
class ProcessApkUpload implements ShouldQueue
{
    use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;

    /** Do not retry a not-implemented job. */
    public int $tries = 1;

    public function __construct(
        public int $submissionId,
    ) {}

    public function handle(): void
    {
        Log::warning('ProcessApkUpload dispatched but not implemented until M5/M6; submission stays in quarantine.', [
            'submission_id' => $this->submissionId,
        ]);

        throw new \RuntimeException(
            'ProcessApkUpload is not implemented until M5/M6 (APK validation & security pipeline). '.
            'Submission '.$this->submissionId.' remains quarantined — fail-closed.'
        );
    }

    public function failed(\Throwable $exception): void
    {
        Log::error('ProcessApkUpload failed (expected until M5/M6).', [
            'submission_id' => $this->submissionId,
            'error' => $exception->getMessage(),
        ]);
    }
}
