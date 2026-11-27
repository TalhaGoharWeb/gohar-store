<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Responses\ApiResponse;
use Illuminate\Http\JsonResponse;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Redis;
use Illuminate\Support\Facades\Storage;

/**
 * GET /api/v1/health — load-balancer / uptime probe
 * (docs/02-database-api.md §E, "System/health/config").
 *
 * No auth, no PII. Returns 200 with status "ok" when every *applicable*
 * check passes, and 503 when any applicable check fails.
 *
 * The Redis check only applies when Redis is actually the configured
 * cache or queue driver (in tests the cache is `array` and the queue is
 * `sync`, so Redis is reported as "not_configured" instead of failing
 * the probe for a service the app is not using). The storage check
 * writes, reads and deletes a probe file on the `local` disk.
 */
class HealthController
{
    public function __invoke(): JsonResponse
    {
        $checks = [
            'database' => $this->checkDatabase(),
            'redis' => $this->checkRedis(),
            'storage' => $this->checkStorage(),
        ];

        $failed = array_filter($checks, fn (string $state): bool => $state === 'fail');
        $ok = $failed === [];

        return ApiResponse::success(
            [
                'status' => $ok ? 'ok' : 'degraded',
                'version' => config('gohar.version'),
                'checks' => $checks,
            ],
            [],
            $ok ? 200 : 503
        );
    }

    private function checkDatabase(): string
    {
        try {
            DB::connection()->getPdo();

            return 'ok';
        } catch (\Throwable) {
            return 'fail';
        }
    }

    private function checkRedis(): string
    {
        $redisInUse = config('cache.default') === 'redis'
            || config('queue.default') === 'redis';

        if (! $redisInUse) {
            return 'not_configured';
        }

        try {
            Redis::connection()->ping();

            return 'ok';
        } catch (\Throwable) {
            return 'fail';
        }
    }

    private function checkStorage(): string
    {
        $probe = 'health/probe-'.uniqid('', true).'.txt';

        try {
            Storage::disk('local')->put($probe, 'ok');
            $read = Storage::disk('local')->get($probe);
            Storage::disk('local')->delete($probe);

            return $read === 'ok' ? 'ok' : 'fail';
        } catch (\Throwable) {
            return 'fail';
        }
    }
}
