<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * M2 — `system_settings` table (docs/02-database-api.md §C).
 *
 * Key–value configuration store. `key` is the primary key (dotted name,
 * e.g. `commission.rate`). The full 41-table schema from §C is NOT built
 * here — it lands in M4/M5, one migration per table/group, as the
 * architecture requires. This migration exists in M2 only because the
 * commission config (15%, admin-editable) is an M2 exit criterion.
 *
 * NOTE: the default Laravel users migration
 * (0001_01_01_000000_create_users_table) is the M2 stand-in for the
 * `users` table. Its columns will be extended in M4 to match §C
 * (roles, verification state, etc.) — no second users migration is
 * created here.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('system_settings', function (Blueprint $table): void {
            $table->string('key', 100)->primary();
            $table->text('value')->nullable();
            $table->string('type', 20)->default('string');
            $table->text('description')->nullable();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('system_settings');
    }
};
