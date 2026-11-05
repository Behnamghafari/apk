# ApiX Native

نسخه مستقل و کاملاً نیتیو ApiX؛ توسعه آن از نسخه فعلی جدا نگه داشته می‌شود.

## فناوری‌ها
- Kotlin
- Jetpack Compose و Material 3
- OkHttp برای درخواست‌های HTTP
- حداقل Android API 23

## اجرای Build
از ریشه مخزن:

```bash
gradle -p apps/apix-native :app:assembleDebug
```

خروجی:
`apps/apix-native/app/build/outputs/apk/debug/app-debug.apk`

## وضعیت فعلی
این پوشه نقطه شروع نسخه نیتیو است. در حال حاضر یک کلاینت HTTP اولیه با انتخاب متد، URL، هدرها، JSON Body و نمایش status، زمان پاسخ، هدرها و بدنه پاسخ دارد.

## اصول معماری
- هیچ WebView یا JavaScript bridge در این اپ استفاده نمی‌شود.
- درخواست‌های شبکه خارج از Main Thread و با OkHttp اجرا می‌شوند.
- سیاست CORS مرورگر روی درخواست‌های مستقیم نیتیو اعمال نمی‌شود.
- محدودیت‌های سمت سرور مانند احراز هویت، مجوزها، IP allowlist، فایروال، rate limit و ضدربات همچنان پابرجا هستند.
- نسخه فعلی `apps/behnam-tester` مستقل باقی می‌ماند و تا آماده‌شدن نسخه جدید حذف یا جایگزین نمی‌شود.
