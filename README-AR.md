# مستودع Alwan Live لـ CloudStream / CSWeb TV

هذا مشروع مصدر إضافة Alwan Live. يحتوي الكتالوج داخل الإضافة نفسها؛ لا يحتاج إلى رفع channels.json إلى استضافة alwanlive.stream.

## البناء والنشر

عند الدفع إلى main يبدأ GitHub Actions ببناء الإضافة. إذا نجح البناء ينشر ملف cs3 وملفات الفهرس إلى الفرع builds.

رابط المستودع في CSWeb TV بعد نجاح البناء:

https://raw.githubusercontent.com/achrefdiabi932-prog/AlwanLive-CloudStream-Repo/builds/repo.json

## بيانات القنوات

يتضمن المصدر 16 قناة و33 رابط سيرفر. بعض المصادر المعتمدة على iframe أو proxy غير صالح لم تُضمّن. روابط البث قد تتغير أو تتوقف؛ تحديثها يتطلب تحديث المصدر وإعادة البناء.

لا تستخدم رابط builds قبل نجاح GitHub Actions وظهور ملف cs3 في الفرع builds.
