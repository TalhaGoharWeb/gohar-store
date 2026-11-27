<?php

namespace Database\Seeders;

use App\Models\User;
use Illuminate\Database\Console\Seeds\WithoutModelEvents;
use Illuminate\Database\Seeder;

class DatabaseSeeder extends Seeder
{
    use WithoutModelEvents;

    /**
     * Seed the application's database.
     *
     * System settings (incl. the 15% commission rate) are seeded on every
     * environment — they are configuration, not demo data. The demo "Test
     * User" is created ONLY on local/testing, never on staging/production.
     * Roles/permissions/categories seeders land in M4.
     */
    public function run(): void
    {
        $this->call(SystemSettingSeeder::class);

        if (app()->environment('local', 'testing')) {
            User::factory()->create([
                'name' => 'Test User',
                'email' => 'test@example.com',
            ]);
        }
    }
}
