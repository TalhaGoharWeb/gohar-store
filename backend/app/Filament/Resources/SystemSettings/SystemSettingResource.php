<?php

namespace App\Filament\Resources\SystemSettings;

use App\Filament\Resources\SystemSettings\Pages\EditSystemSetting;
use App\Filament\Resources\SystemSettings\Pages\ListSystemSettings;
use App\Filament\Resources\SystemSettings\Schemas\SystemSettingForm;
use App\Filament\Resources\SystemSettings\Tables\SystemSettingsTable;
use App\Models\SystemSetting;
use BackedEnum;
use Filament\Resources\Resource;
use Filament\Schemas\Schema;
use Filament\Support\Icons\Heroicon;
use Filament\Tables\Table;

/**
 * M2 — admin editing for `system_settings` (commission included).
 *
 * Deliberately EDIT-ONLY in M2: settings are seeded by
 * SystemSettingSeeder, and admins may change their *values* — they may
 * NOT invent new keys or delete keys from the admin UI. A new tunable
 * is added by a developer in the seeder + docs, so its meaning and
 * default are reviewed in code, not typed into a form.
 *
 * KNOWN LIMITATION (M2): any authenticated Filament user can edit these
 * values. Role-based restriction (only `finance`/`admin` may change the
 * commission rate) lands with RBAC in M9. Until then, keep the admin
 * account list minimal and audit every change (audit_logs, M9).
 */
class SystemSettingResource extends Resource
{
    protected static ?string $model = SystemSetting::class;

    protected static string|BackedEnum|null $navigationIcon = Heroicon::OutlinedCog6Tooth;

    protected static ?string $navigationLabel = 'System Settings';

    protected static ?string $recordTitleAttribute = 'key';

    public static function form(Schema $schema): Schema
    {
        return SystemSettingForm::configure($schema);
    }

    public static function table(Table $table): Table
    {
        return SystemSettingsTable::configure($table);
    }

    public static function getRelations(): array
    {
        return [
            //
        ];
    }

    public static function getPages(): array
    {
        return [
            'index' => ListSystemSettings::route('/'),
            'edit' => EditSystemSetting::route('/{record}/edit'),
        ];
    }

    public static function canCreate(): bool
    {
        return false;
    }

    public static function canDeleteAny(): bool
    {
        return false;
    }
}
