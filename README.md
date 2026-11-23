# বাকির খাতা (Bakir Khata)

দোকান, মুদি ও খুচরা ব্যবসার বাকি হিসাব, নগদ বিক্রি, মেমো, ক্যাশবক্স, খরচ, দৈনিক ও মাসিক রিপোর্ট, হালখাতা, পিডিএফ/প্রিন্ট এবং অফলাইন ব্যাকআপ-রিস্টোর পরিচালনার সম্পূর্ণ বাংলা অ্যান্ড্রয়েড অ্যাপ।

---

## GitHub থেকে সরাসরি APK বিল্ড ও ফোনে ইন্সটল করার নিয়ম

এই রিপোজিটরিতে আগে থেকেই **GitHub Actions CI/CD** (`/.github/workflows/build-android.yml`) যুক্ত করা আছে। আলাদাভাবে Android Studio বা SDK ছাড়াই GitHub থেকে সরাসরি APK তৈরি করে ফোনে ইন্সটল করতে পারবেন:

1. **GitHub-এ কোড আপলোড (Push) করুন:**
   প্রজেক্টটি আপনার GitHub রিপোজিটরিতে Push করুন (`main` বা `master` ব্রাঞ্চে)।
2. **Actions ট্যাবে যান:**
   GitHub রিপোজিটরির উপরের **Actions** ট্যাবে ক্লিক করুন → `Build Bakir Khata Android APK` ওয়ার্কফ্লোটি স্বয়ংক্রিয়ভাবে চালু হয়ে টেস্ট ও APK বিল্ড করবে (চাইলে **Run workflow** বাটনে ক্লিক করে ম্যানুয়ালিও চালাতে পারবেন)।
3. **APK ডাউনলোড ও ইন্সটল করুন:**
   - বিল্ড সফল হলে নিচে **Artifacts** সেকশনে **`BakirKhata-debug-apk`** দেখতে পাবেন।
   - ZIP ফাইলটি ডাউনলোড করে আনজিপ করুন → ভেতরে `BakirKhata-debug.apk` পাবেন।
   - ফোনে ফাইলটি ওপেন করে *Install unknown apps* অনুমতি দিয়ে সরাসরি ইন্সটল করুন।
4. **GitHub Releases থেকে সরাসরি `.apk` পেতে চাইলে:**
   - **Actions → Build Bakir Khata Android APK → Run workflow** থেকে **`Publish a GitHub Release with downloadable APK`** অপশনটি টিক দিয়ে রান করুন, অথবা `v1.0.0` ধরনের tag push করুন (`git tag v1.0.0 && git push origin v1.0.0`)।
   - তখন রিপোজিটরির **Releases** পেজে সরাসরি `BakirKhata-debug.apk` পাওয়া যাবে (ZIP আনজিপ করারও দরকার হবে না)।

---

## ঐচ্ছিক: Release APK / Play Store AAB সাইন করার সেটআপ

ডিফল্টভাবে `BakirKhata-debug.apk` রিপোজিটরির `debug.keystore.base64` দিয়ে একই সাইনিং কী-তে সাইন হয়, তাই প্রতিবার নতুন বিল্ড আপডেট করলেও আগের ডেটা মুছে না গিয়ে আপডেট ইন্সটল হয়।

আপনি যদি নিজের Upload Keystore দিয়ে **Signed Release APK ও `.aab`** বিল্ড করতে চান, তবে GitHub রিপোজিটরির **Settings → Secrets and variables → Actions** এ নিচের ৩টি Secret যোগ করুন (key alias হতে হবে `upload`):

- `KEYSTORE_BASE64` — আপনার `.jks` ফাইলের Base64 টেক্সট (`base64 -w 0 my-upload-key.jks`)
- `STORE_PASSWORD` — Keystore পাসওয়ার্ড
- `KEY_PASSWORD` — Key পাসওয়ার্ড

Secret যুক্ত থাকলে GitHub Actions স্বয়ংক্রিয়ভাবে `BakirKhata-release.apk` এবং `BakirKhata-release.aab`-ও তৈরি করে আপলোড করবে।

---

## লোকাল পিসি / Android Studio থেকে বিল্ড করতে চাইলে

- প্রজেক্ট ফোল্ডারটি Android Studio-তে Open করুন অথবা টার্মিনালে চালান:
  - Debug keystore তৈরি: `base64 -d debug.keystore.base64 > debug.keystore`
  - টেস্ট চালানো: `./gradlew :app:testDebugUnitTest`
  - APK বিল্ড: `./gradlew :app:assembleDebug`
