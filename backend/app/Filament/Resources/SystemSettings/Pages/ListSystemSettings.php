<?php

namespace App\Filament\Resources\SystemSettings\Pages;

use App\Filament\Resources\SystemSettings\SystemSettingResource;
use Filament\Resources\Pages\ListRecords;

/**
 * M2: no "create" header action — new settings are added by developers
 * in SystemSettingSeeder, not from the admin UI (see SystemSettingResource).
 */
class ListSystemSettings extends ListRecords
{
    protected static string $resource = SystemSettingResource::class;

    protected function getHeaderActions(): array
    {
        return [
            //
        ];
    }
}
