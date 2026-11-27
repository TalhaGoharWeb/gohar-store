<?php

/*
|--------------------------------------------------------------------------
| GOHAR Store configuration
|--------------------------------------------------------------------------
|
| Marketplace-level constants that are NOT admin-tunable. Anything an
| admin should be able to change (commission rate, payout minimum,
| download-link TTL, ...) lives in the `system_settings` table instead
| and is read via the SystemSetting model / CommissionConfig service.
|
*/

return [
    /*
     * Application version reported by GET /api/v1/health.
     * Bump at each milestone release. This is a build label, not a
     * secret and not a tunable.
     */
    'version' => env('GOHAR_VERSION', '0.2.0-dev'),

    /*
     * Current public API version prefix (routes live under /api/v1).
     */
    'api_version' => 'v1',
];
