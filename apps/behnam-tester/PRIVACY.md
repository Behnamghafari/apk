# سیاست حریم خصوصی ApiX

**آخرین به‌روزرسانی: ۵ نوامبر ۲۰۲۶**

ApiX یک ابزار محلی برای توسعه و آزمایش API در اندروید است. این برنامه برای استفاده از قابلیت‌های اصلی خود به حساب کاربری نیاز ندارد و درخواست‌های API را از طریق سرور متعلق به سازنده عبور نمی‌دهد.

## چه اطلاعاتی در دستگاه ذخیره می‌شود؟

برای ارائه قابلیت‌های برنامه، موارد زیر ممکن است در فضای خصوصی برنامه روی دستگاه شما ذخیره شوند:

- درخواست‌های ذخیره‌شده، مجموعه‌ها، محیط‌ها و متغیرهای سراسری
- تاریخچه درخواست‌ها و پاسخ‌ها و گزارش اجرای تست‌ها
- توکن‌ها، مقادیر احراز هویت و هدرهایی که خودتان ذخیره می‌کنید
- کوکی‌های HTTP
- اتصال‌ها و تاریخچه پیام‌های WebSocket

این داده‌ها برای عملکرد برنامه روی همان دستگاه نگهداری می‌شوند. ApiX آن‌ها را به سرور سازنده ارسال نمی‌کند. با این حال، اگر مجموعه یا محیطی را خروجی بگیرید و برای شخص دیگری بفرستید، ممکن است اطلاعات محرمانه داخل آن فایل قرار داشته باشد.

## درخواست‌های شبکه

وقتی خودتان یک درخواست REST یا GraphQL اجرا می‌کنید، اتصال WebSocket برقرار می‌کنید، مانیتور تعریف می‌کنید یا فایل آپلود می‌کنید، اطلاعات مربوط به آن عملیات مستقیماً به نشانی سروری ارسال می‌شود که انتخاب کرده‌اید. پردازش اطلاعات پس از ارسال تابع سیاست حریم خصوصی همان سرویس مقصد است.

ApiX برای تست سرویس‌های قدیمی و محیط‌های توسعه از HTTP بدون رمزنگاری نیز پشتیبانی می‌کند. در صورت امکان از HTTPS و WSS استفاده کنید و اطلاعات حساس را روی اتصال ناامن ارسال نکنید.

قابلیت تست فشار می‌تواند تعداد زیادی درخواست به سرور مقصد ارسال کند. فقط سامانه‌هایی را آزمایش کنید که مالک آن‌ها هستید یا اجازه صریح برای آزمایش آن‌ها دارید.

## دسترسی‌ها و فایل‌ها

- دسترسی اینترنت برای ارسال درخواست‌های API، اتصال WebSocket و اجرای ابزارهای شبکه استفاده می‌شود.
- برای انتخاب فایل، برنامه از انتخاب‌گر فایل اندروید استفاده می‌کند؛ فایل فقط پس از اقدام شما و اجرای آپلود به مقصد انتخاب‌شده ارسال می‌شود.
- برنامه برای عملکرد اصلی خود به مخاطبین، موقعیت مکانی، دوربین یا میکروفون نیاز ندارد.

## تحلیل‌گرها و تبلیغات

نسخه فعلی ApiX از تبلیغات، ابزارهای تحلیل رفتار، ردیاب‌های تبلیغاتی یا سرویس گزارش‌گیری شخص ثالث استفاده نمی‌کند.

## نگهداری و حذف داده‌ها

داده‌های ذخیره‌شده در فضای خصوصی برنامه باقی می‌مانند. می‌توانید تاریخچه‌ها، مجموعه‌ها، محیط‌ها، کوکی‌ها و داده‌های مربوط به ابزارها را از داخل برنامه پاک کنید. پاک‌کردن داده‌های برنامه یا حذف نصب آن نیز داده‌های محلی برنامه را حذف می‌کند.

## امنیت

ApiX از فضای ذخیره‌سازی خصوصی اندروید استفاده می‌کند؛ با این حال، برخی مقادیر حساس مانند توکن‌ها و کوکی‌ها برای استفاده مجدد در فضای محلی برنامه نگهداری می‌شوند. از قفل صفحه و امنیت دستگاه خود محافظت کنید و اطلاعات محرمانه را در فایل‌های خروجی یا اسکریپت‌های غیرقابل‌اعتماد قرار ندهید. اسکریپت‌های واردشده در نسخه فعلی به‌صورت پیش‌فرض غیرفعال هستند و فقط در صورت فعال‌کردن مجوز اجرای اسکریپت اجرا می‌شوند.

## تماس

برای پرسش‌های مربوط به حریم خصوصی ApiX می‌توانید با سازنده از طریق تلگرام تماس بگیرید:

https://t.me/behnamghafari

---

# ApiX Privacy Policy

**Last updated: November 5, 2026**

ApiX is an Android API development and testing tool. An account is not required to use its core features, and API traffic is not routed through a developer-operated server.

## Data stored on your device

To provide its features, ApiX may store the following data in the app's private storage on your device:

- Saved requests, collections, environments and global variables
- Request history, response data and test-run reports
- Tokens, authentication values and headers that you choose to save
- HTTP cookies
- WebSocket connections and message history

This data is stored locally for the app's operation. ApiX does not send it to the developer's server. Exported collection or environment files may contain credentials or other sensitive values, so review them before sharing.

## Network requests

When you execute a REST or GraphQL request, connect to a WebSocket, configure a monitor or upload a file, the relevant data is sent directly to the endpoint you selected. The destination service's privacy policy governs its handling of that data.

ApiX supports unencrypted HTTP for legacy services and development environments. Prefer HTTPS and WSS whenever possible, and do not send sensitive information over an unencrypted connection.

Load testing can send a large number of requests to a destination. Only test systems you own or are explicitly authorized to test.

## Permissions and files

- Internet access is used for API requests, WebSocket connections and network tools.
- File selection uses the Android document picker. A selected file is sent only after you initiate an upload to your chosen endpoint.
- The app does not require contacts, location, camera or microphone access for its core functionality.

## Analytics and advertising

The current version of ApiX does not use advertising, behavioral analytics, advertising trackers or third-party crash-reporting services.

## Data retention and deletion

Locally stored data remains in the app's private storage. You can clear histories, collections, environments, cookies and tool data from within the app. Clearing the app's storage or uninstalling the app also removes its local data.

## Security

ApiX uses Android app-private storage. Some sensitive values, such as tokens and cookies, are retained locally so they can be reused. Secure your device with a screen lock and avoid placing secrets in exported files or untrusted scripts. Imported scripts are disabled by default and run only after you explicitly enable script execution.

## Contact

For privacy questions about ApiX, contact the developer through Telegram:

https://t.me/behnamghafari
