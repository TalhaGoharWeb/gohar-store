<?php

namespace Tests\Feature;

use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/**
 * M2 — GET /api/v1/health (docs/02-database-api.md §E).
 *
 * The probe needs no auth and returns the standard envelope:
 * { data: { status, version, checks }, meta: { api_version } }.
 * In the test environment the cache is `array` and the queue is `sync`,
 * so Redis is reported as "not_configured" rather than failing the
 * probe for a service the app is not using (see HealthController).
 */
class HealthTest extends TestCase
{
    use RefreshDatabase;

    public function test_health_returns_200_with_standard_envelope(): void
    {
        $response = $this->getJson('/api/v1/health');

        $response->assertOk();
        $response->assertJsonStructure([
            'data' => ['status', 'version', 'checks' => ['database', 'redis', 'storage']],
            'meta' => ['api_version'],
        ]);
        $response->assertJsonPath('data.status', 'ok');
        $response->assertJsonPath('data.checks.database', 'ok');
        $response->assertJsonPath('data.checks.storage', 'ok');
        $response->assertJsonPath('data.checks.redis', 'not_configured');
        $response->assertJsonPath('meta.api_version', 'v1');
    }

    public function test_health_is_public_and_returns_no_pii(): void
    {
        // No Authorization header is sent; the probe must still answer.
        $response = $this->getJson('/api/v1/health');

        $response->assertOk();
        $this->assertStringNotContainsString('password', $response->getContent());
        $this->assertStringNotContainsString('secret', $response->getContent());
    }
}
