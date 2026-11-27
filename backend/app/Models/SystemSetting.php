<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Support\Facades\Cache;

/**
 * System settings — the single source of truth for tunable marketplace
 * configuration (docs/02-database-api.md §C, `system_settings` table).
 *
 * The table is a key–value store, not a row per setting, so adding a tunable
 * never requires a migration. Keys are dotted names, e.g. `commission.rate`.
 *
 * Rules (from the architecture):
 * - Money is never a FLOAT; the commission rate is stored as a decimal
 *   fraction string, e.g. `0.1500` (= 15%).
 * - Provider credentials are NEVER stored here, not even encrypted —
 *   only provider *names* and *enabled* flags; credentials live in
 *   env / the secret manager.
 * - Reads are cached (Redis in production, array in tests) and invalidated
 *   on write via the `saved` / `deleted` model events below.
 */
class SystemSetting extends Model
{
    protected $primaryKey = 'key';

    public $incrementing = false;

    protected $keyType = 'string';

    protected $fillable = [
        'key',
        'value',
        'type',
        'description',
    ];

    protected static function booted(): void
    {
        // Invalidate the per-key cache entry whenever a setting changes.
        static::saved(fn (self $setting) => Cache::forget(self::cacheKey($setting->key)));
        static::deleted(fn (self $setting) => Cache::forget(self::cacheKey($setting->key)));
    }

    public static function cacheKey(string $key): string
    {
        return "gohar:settings:{$key}";
    }

    public static function get(string $key, mixed $default = null): ?self
    {
        return static::query()->find($key) ?? $default;
    }

    /**
     * Return the setting's value decoded according to its `type` column:
     * `decimal`/`int` → numeric, `bool` → bool, `json` → array,
     * anything else → raw string.
     */
    public static function getValue(string $key, mixed $default = null): mixed
    {
        $setting = static::query()->find($key);

        if ($setting === null) {
            return $default;
        }

        return $setting->decodedValue();
    }

    public function decodedValue(): mixed
    {
        return match ($this->type) {
            'decimal' => (float) $this->value,
            'int' => (int) $this->value,
            'bool' => filter_var($this->value, FILTER_VALIDATE_BOOLEAN),
            'json' => json_decode((string) $this->value, true) ?? [],
            default => $this->value,
        };
    }

    public function valueAsString(): string
    {
        return (string) $this->value;
    }

    public function valueAsFloat(): float
    {
        return (float) $this->value;
    }

    public function valueAsInt(): int
    {
        return (int) $this->value;
    }

    public function valueAsBool(): bool
    {
        return filter_var($this->value, FILTER_VALIDATE_BOOLEAN);
    }

    /** @return array<array-key, mixed> */
    public function valueAsArray(): array
    {
        return json_decode((string) $this->value, true) ?? [];
    }
}
