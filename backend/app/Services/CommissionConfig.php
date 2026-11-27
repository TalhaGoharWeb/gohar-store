<?php

namespace App\Services;

use App\Models\SystemSetting;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Log;
use InvalidArgumentException;

/**
 * Commission configuration — the ONLY place the marketplace commission
 * rate is read (docs/02-database-api.md §C, docs/03 §G).
 *
 * Source of truth: the `commission.rate` row in `system_settings`
 * (decimal fraction, e.g. `0.1500` = 15%), editable by an admin in
 * Filament. The rate is cached (Redis in production) and invalidated
 * on write by the SystemSetting model events.
 *
 * The fallback below (0.1500) is used ONLY when the row is missing —
 * e.g. before M2 seeding ran — and every fallback use is logged as a
 * warning so a missing seed is visible instead of silent. The fallback
 * is a safety net, not configuration: do NOT "configure" the commission
 * by editing this constant. Change the database row.
 *
 * Money maths: commission_minor = FLOOR(gross_minor × rate), computed
 * with bcmath on the string rate so there is no float drift. The
 * remainder goes to the developer (§C, `commission_entries`).
 */
class CommissionConfig
{
    public const SETTING_KEY = 'commission.rate';

    public const ROUNDING_KEY = 'commission.rounding';

    /** Fallback rate (fraction string) — see class docblock. */
    private const FALLBACK_RATE = '0.1500';

    /**
     * Commission rate as a decimal-fraction string, e.g. '0.1500'.
     */
    public function rate(): string
    {
        return Cache::remember(
            SystemSetting::cacheKey(self::SETTING_KEY),
            3600,
            function (): string {
                $setting = SystemSetting::get(self::SETTING_KEY);

                if ($setting === null || $setting->value === null || $setting->value === '') {
                    Log::warning('GOHAR commission: system_settings row "commission.rate" is missing; using fallback rate.', [
                        'fallback_rate' => self::FALLBACK_RATE,
                    ]);

                    return self::FALLBACK_RATE;
                }

                return (string) $setting->value;
            }
        );
    }

    public function rateFloat(): float
    {
        return (float) $this->rate();
    }

    /** Commission rate as a percentage, e.g. 15.0 for a 0.1500 rate. */
    public function ratePercent(): float
    {
        return $this->rateFloat() * 100;
    }

    public function rounding(): string
    {
        $value = SystemSetting::getValue(self::ROUNDING_KEY, 'floor');

        return is_string($value) && $value !== '' ? $value : 'floor';
    }

    /**
     * Commission for a gross sale amount, in minor units.
     * FLOOR(gross × rate) — remainder to the developer.
     *
     * @throws InvalidArgumentException on a negative gross amount.
     */
    public function commissionFor(int $grossMinor): int
    {
        if ($grossMinor < 0) {
            throw new InvalidArgumentException('Gross amount must not be negative.');
        }

        if ($grossMinor === 0) {
            return 0;
        }

        // bcmath on the string rate: no float drift. (int) truncates
        // toward zero, which is FLOOR for positive amounts.
        $product = bcmul((string) $grossMinor, $this->rate(), 4);

        return (int) $product;
    }

    /** Developer share of a gross sale, in minor units. */
    public function developerShareFor(int $grossMinor, int $providerFeeMinor = 0): int
    {
        return $grossMinor - $this->commissionFor($grossMinor) - $providerFeeMinor;
    }
}
