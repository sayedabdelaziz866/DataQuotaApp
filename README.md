# Data Quota App

## طريقة البناء من الموبايل (من غير Android Studio)

المشروع فيه GitHub Actions workflow (`.github/workflows/build.yml`) بيبني الـ APK
تلقائيًا على سيرفرات جوجل كل ما ترفع الكود - إنت مش محتاج تبني حاجة على جهازك.

### الخطوات:

1. اعمل حساب على GitHub لو معندكش (من المتصفح على الموبايل عادي).
2. اعمل repository جديد فاضي (New repository) - سيبه Public أو Private، مش فارقة،
   وميهمش تحط أي ملفات وقت الإنشاء.
3. نزّل تطبيق **Termux** من F-Droid (مش من Play Store، النسخة هناك قديمة ومش شغالة كويس).
4. افتح Termux ونفذ الأوامر دي واحدة واحدة:

   ```
   termux-setup-storage
   pkg install git unzip -y
   cd storage/downloads
   unzip DataQuotaApp.zip
   cd DataQuotaApp
   git init
   git add .
   git commit -m "first commit"
   git branch -M main
   git remote add origin https://github.com/USERNAME/REPO_NAME.git
   git push -u origin main
   ```

   غيّر `USERNAME` و`REPO_NAME` باسمك واسم الـ repo اللي عملته.

5. وقت الـ `push`، هيطلب منك username وpassword. الـ password هنا مش الباسورد
   العادي بتاع حسابك - لازم تعمل **Personal Access Token**:
   - GitHub > Settings > Developer settings > Personal access tokens > Tokens (classic)
   - Generate new token، فعّل صلاحية `repo`، وانسخه
   - استخدمه بدل الباسورد وقت الـ push

6. بعد ما الـ push يخلص، روح على صفحة الـ repo بتاعك على GitHub > تبويب **Actions**.
   هتلاقي الـ workflow شغال (دايرة صفراء بتلف)، استنى لحد ما تخضر (2-4 دقايق تقريبًا).

7. دوس على الـ run اللي خلص، هتلاقي تحت في **Artifacts** حاجة اسمها `app-debug`،
   نزّلها (هتيجي كملف zip).

8. فك الضغط عن الـ zip، هتلاقي جواه `app-debug.apk`. دوس عليه علشان تثبته -
   هيطلب منك تسمح بـ "Install from unknown sources" أول مرة، وافق.

كده التطبيق هيتثبت على الموبايل زي أي تطبيق عادي، من غير أي حاجة على جهاز كمبيوتر خالص.
