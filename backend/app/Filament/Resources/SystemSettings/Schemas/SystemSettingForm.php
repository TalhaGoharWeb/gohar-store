<?php

namespace App\Filament\Resources\SystemSettings\Schemas;

use Filament\Forms\Components\Select;
use Filament\Forms\Components\Textarea;
use Filament\Forms\Components\TextInput;
use Filament\Schemas\Schema;

/**
 * Edit form for one system setting. The `key` and `type` are shown but
 * locked: changing a key would orphan every reader of that setting, and
 * changing a type would change how the value is decoded. Only `value`
 * is editable — that is the admin's job (e.g. commission.rate).
 *
 * commission.rate is a DECIMAL FRACTION: 0.1500 = 15%. It is never a
 * percentage number in this field — entering "15" would mean a 1500%
 * commission. The helper text says so on the form itself.
 */
class SystemSettingForm
{
    public static function configure(Schema $schema): Schema
    {
        return $schema
            ->components([
                TextInput::make('key')
                    ->label('Key')
                    ->disabled()
                    ->required()
                    ->helperText('Dotted setting name. Locked — keys are added by developers in the seeder, not here.'),

                Textarea::make('value')
                    ->label('Value')
                    ->required()
                    ->rows(3)
                    ->helperText('For commission.rate enter a decimal fraction, e.g. 0.1500 = 15%. For JSON settings enter valid JSON. Applies to future sales / behaviour only — past commission entries keep their snapshot rate.'),

                Select::make('type')
                    ->label('Type')
                    ->options([
                        'string' => 'string',
                        'decimal' => 'decimal',
                        'int' => 'int',
                        'bool' => 'bool',
                        'json' => 'json',
                    ])
                    ->disabled()
                    ->required()
                    ->helperText('How the value is decoded. Locked — set by the seeder.'),

                Textarea::make('description')
                    ->label('Description')
                    ->disabled()
                    ->rows(3)
                    ->helperText('What this setting does and who may change it.'),
            ]);
    }
}
