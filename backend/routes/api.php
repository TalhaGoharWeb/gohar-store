<?php

use App\Http\Controllers\Api\V1\HealthController;
use Illuminate\Support\Facades\Route;

/*
|--------------------------------------------------------------------------
| GOHAR Store API — versioned routes (docs/02-database-api.md §E)
|--------------------------------------------------------------------------
|
| All routes live under /api/v1. The version prefix is part of the URL
| so a future v2 can ship alongside v1. Every response uses the
| ApiResponse envelope: { data, meta } on success,
| { data: null, errors: [...] } on failure.
|
| M2 ships the health probe only. Auth, catalogue, search, categories,
| reviews and favourites land in M4; developer submission routes in M5;
| downloads in M7; orders / payments / webhooks in M8.
*/

Route::prefix('v1')->group(function (): void {
    Route::get('/health', HealthController::class)->name('api.v1.health');
});
