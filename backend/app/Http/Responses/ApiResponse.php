<?php

namespace App\Http\Responses;

use Illuminate\Http\JsonResponse;

/**
 * The one response envelope for the whole API
 * (docs/02-database-api.md §E.3). Every endpoint returns one of these
 * two shapes — no bare arrays, no bare scalars:
 *
 * Success: { "data": …, "meta": { "api_version": "v1", … } }
 * Error:   { "data": null, "errors": [{ "code", "message", … }] }
 */
final class ApiResponse
{
    /** @param array<string, mixed> $meta */
    public static function success(mixed $data, array $meta = [], int $status = 200): JsonResponse
    {
        return response()->json([
            'data' => $data,
            'meta' => array_merge(['api_version' => config('gohar.api_version', 'v1')], $meta),
        ], $status);
    }

    /** @param array<string, mixed> $details */
    public static function error(string $code, string $message, int $status, array $details = []): JsonResponse
    {
        return response()->json([
            'data' => null,
            'errors' => [
                array_merge(['code' => $code, 'message' => $message], $details),
            ],
        ], $status);
    }
}
