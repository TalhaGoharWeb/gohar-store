<?php

namespace Tests\Unit;

use App\Models\SystemSetting;
use App\Services\CommissionConfig;
use Database\Seeders\SystemSettingSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Log;
use Tests\TestCase;

/**
 * M2 — CommissionConfig (docs/02-database-api.md §C, docs/03 §G).
 *
 * The rate comes from the `commission.rate` row in `system_settings`
 * (decimal fraction, seeded as 0.1500 = 15%). It is NEVER hardcoded in
 * the service: changing the row changes the rate, deleting the row
 * falls back to 0.1500 with a logged warning. Commission is
 * FLOOR(gross × rate) — remainder to the developer.
 *
 * NOTE: the expected values in these assertions (15, 0.15, 150, …) are
 * test expectations for the SEEDED value, not a second hardcoding of
 * the rate — the service itself reads the database row.
 */
class CommissionConfigTest extends TestCase
{
    use RefreshDatabase;

    private CommissionConfig $config;

    protected function setUp(): void
    {
        parent::setUp();

        $this->seed(SystemSettingSeeder::class);
        $this->config = new CommissionConfig();
    }

    public function test_rate_reads_seeded_15_percent_from_settings(): void
    {
        $this->assertSame('0.1500', $this->config->rate());
        $this->assertSame(0.15, $this->config->rateFloat());
        $this->assertSame(15.0, $this->config->ratePercent());
    }

    public function test_rate_follows_the_database_row_when_an_admin_changes_it(): void
    {
        SystemSetting::query()->where('key', 'commission.rate')->update(['value' => '0.2000']);

        // The model event invalidates the cache, so the next read is fresh.
        $this->assertSame('0.2000', $this->config->rate());
        $this->assertSame(20.0, $this->config->ratePercent());
        $this->assertSame(200, $this->config->commissionFor(1000));
    }

    public function test_missing_row_falls_back_to_15_percent_and_logs_a_warning(): void
    {
        Log::spy();

        SystemSetting::query()->where('key', 'commission.rate')->delete();

        $this->assertSame('0.1500', $this->config->rate());
        $this->assertSame(15.0, $this->config->ratePercent());

        Log::shouldHaveReceived('warning')
            ->withArgs(fn (string $message): bool => str_contains($message, 'commission.rate'));
    }

    public function test_commission_is_floor_of_gross_times_rate(): void
    {
        $this->assertSame(150, $this->config->commissionFor(1000)); // exactly 150.00
        $this->assertSame(149, $this->config->commissionFor(999));  // 149.85 → floor 149
        $this->assertSame(49, $this->config->commissionFor(333));   // 49.95 → floor 49
        $this->assertSame(0, $this->config->commissionFor(0));
        $this->assertSame(0, $this->config->commissionFor(6));      // 0.90 → floor 0
    }

    public function test_developer_share_is_gross_minus_commission(): void
    {
        $this->assertSame(850, $this->config->developerShareFor(1000));
        $this->assertSame(850, $this->config->developerShareFor(999)); // 999 - 149
        $this->assertSame(800, $this->config->developerShareFor(1000, 50)); // minus provider fee
    }

    public function test_negative_gross_is_rejected(): void
    {
        $this->expectException(\InvalidArgumentException::class);

        $this->config->commissionFor(-1);
    }
}
