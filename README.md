# تسويف للأندرويد

تطبيق أندرويد لموقع [tasweef.web.app](https://tasweef.web.app): يفتح الموقع بملء الشاشة في كروم، ويضيف ميزة لا يقدر عليها الموقع وحده، وهي **تنزيل الفيديو أو الصوت على الهاتف مباشرة**.

## كيف يعمل

- `LauncherActivity`: يفتح الموقع ويضيف `?twa=2` إلى الرابط، فيعرف الموقع أنه يعمل داخل هذا التطبيق.
- عند الضغط على «تنزيل» يفتح الموقع رابطًا من نوع `intent://download?u=…#Intent;scheme=tasweef;package=app.web.tasweef.twa;end`.
- `DownloadActivity`: يستقبل هذا الرابط، ويطلب إذن الإشعارات مرة واحدة، ثم يسلّم المهمة إلى خدمة التنزيل.
- `DownloadService`: ينزّل بـ [yt-dlp وffmpeg](https://github.com/yausername/youtubedl-android) على الهاتف نفسه، ويعرض التقدم في إشعار، ويحفظ الملف في `Download/Tasweef`.
  - يحدّث yt-dlp مرة في اليوم، وعند فشل التنزيل.

## البناء

يُبنى ويُنشر تلقائيًا من GitHub Actions عند كل تعديل، والملف هو `tasweef.apk` في آخر إصدار.

يحتاج البناء هذه الأسرار في إعدادات المستودع (Settings ← Secrets and variables ← Actions):

| السر | القيمة |
|---|---|
| `KEYSTORE_BASE64` | ملف `signing.keystore` مُرمّزًا بـ base64 |
| `KEYSTORE_PASSWORD` | Key store password من `signing-key-info.txt` |
| `KEY_PASSWORD` | Key password من نفس الملف |

يجب البناء بنفس المفتاح دائمًا، وإلا فلن يُثبَّت التطبيق فوق النسخة السابقة.
