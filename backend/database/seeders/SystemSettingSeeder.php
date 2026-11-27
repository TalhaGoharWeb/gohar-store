<?php

namespace Database\Seeders;

use App\Models\SystemSetting;
use Illuminate\Database\Seeder;

/**
 * Seeds the minimum `system_settings` keys from docs/02-database-api.md
 * §C (seeded-keys list). Idempotent — safe to re-run.
 *
 * `commission.rate` is the marketplace commission as a DECIMAL FRACTION
 * (0.1500 = 15%). It is the value the CommissionConfig service reads;
 * nothing in the codebase hardcodes 15% anywhere else.
 *
 * Values marked "to be confirmed" in §C (payout minimum, max APK size)
 * are seeded with the documented starting values and MUST be confirmed
 * by Talha before M8 (payouts) / M6 (APK pipeline) respectively.
 */
class SystemSettingSeeder extends Seeder
{
    public function run(): void
    {
        $settings = [
            [
                'key' => 'commission.rate',
                'value' => '0.1500',
                'type' => 'decimal',
                'description' => 'Marketplace commission as a decimal fraction of eligible paid sales (0.1500 = 15%). Admin-editable; applies to future sales only — past commission_entries keep their snapshot rate.',
            ],
            [
                'key' => 'commission.rounding',
                'value' => 'floor',
                'type' => 'string',
                'description' => 'Rounding rule for commission_minor: "floor" — commission is rounded DOWN, remainder goes to the developer. Never changed silently.',
            ],
            [
                'key' => 'payouts.minimum_minor',
                'value' => '500000',
                'type' => 'int',
                'description' => 'Minimum payout amount in minor units (500000 = PKR 5,000). TO BE CONFIRMED by Talha before M8.',
            ],
            [
                'key' => 'payouts.currencies',
                'value' => '["PKR"]',
                'type' => 'json',
                'description' => 'Currencies developers can be paid in. PKR-only at launch.',
            ],
            [
                'key' => 'risk.bands',
                'value' => '{"low": 30, "medium": 60, "high": 85}',
                'type' => 'json',
                'description' => 'APK risk-score bands (M6). Thresholds, not hardcoded constants.',
            ],
            [
                'key' => 'downloads.link_ttl_seconds',
                'value' => '900',
                'type' => 'int',
                'description' => 'TTL in seconds for short-lived signed download URLs (900 = 15 minutes).',
            ],
            [
                'key' => 'security.max_apk_bytes',
                'value' => '524288000',
                'type' => 'int',
                'description' => 'Maximum APK upload size in bytes (524288000 = 500 MB). TO BE CONFIRMED by Talha before M6.',
            ],
            [
                'key' => 'payments.providers',
                'value' => '{}',
                'type' => 'json',
                'description' => 'Enabled payment providers and their flags. EMPTY until a provider passes its activation checklist (credentials + merchant approval + payment testing). No provider is active by default.',
            ],
            [
                'key' => 'catalogue.page_size_default',
                'value' => '20',
                'type' => 'int',
                'description' => 'Default catalogue page size.',
            ],
            [
                'key' => 'catalogue.page_size_max',
                'value' => '100',
                'type' => 'int',
                'description' => 'Maximum catalogue page size a client may request.',
            ],
        ];

        foreach ($settings as $setting) {
            SystemSetting::updateOrCreate(
                ['key' => $setting['key']],
                ['value' => $setting['value'], 'type' => $setting['type'], 'description' => $setting['description']]
            );
        }
    }
}
