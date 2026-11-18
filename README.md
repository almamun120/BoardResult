# Board Result (Kotlin, Android)

একটি অ্যাপে: **অ্যাডমিন প্যানেল + ওয়েব পোর্টাল (শিক্ষক/শিক্ষার্থী) + SMS রেজাল্ট সার্ভার + PDF**।

## বিল্ড
GitHub-এ push করুন → Actions → *Build APK* (debug + release APK আর্টিফ্যাক্ট)।
Signed release-এর জন্য secrets: `KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD`।
লোকালি: `gradle assembleDebug` (Gradle 8.9, JDK 17)।

## প্রথম ব্যবহার
1. অ্যাপ খুলে অ্যাডমিন PIN সেট করুন, Home থেকে Permissions দিন, Start server চাপুন।
2. Setup → Institutions (EIIN সহ) → Teachers যোগ করুন।
3. Results → exam type + board + year দিয়ে session খুলুন।
4. শিক্ষক `http://<ফোনের-IP>:8080/teacher/login` এ ঢুকে শিক্ষার্থী যোগ করে নম্বর দেন।
5. Results ট্যাবে সুইচ দিয়ে Publish/Unpublish। প্রকাশের পর শিক্ষক নম্বর বদলাতে পারেন না।
6. SMS: `SSC DHA 123456 2023` (ঐচ্ছিক ৫ম অংশ: রেজিস্ট্রেশন)।

## নোট
- Seed করা বিষয়কোড/নম্বর বণ্টন **নমুনা** — অফিসিয়াল তালিকা মিলিয়ে Setup থেকে ঠিক করুন।
- ইন্টারনেট থেকে ব্যবহারের জন্য Cloudflare Tunnel (HTTPS) ব্যবহার করুন; সরাসরি HTTP শুধু লোকাল নেটওয়ার্কে।
- Backup/restore: Settings → Backup & restore
