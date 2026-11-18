# FVEditor

مرحله اول پروژه مستقل FVEditor؛ اسکلت اولیه اندروید شامل پلیر و استوری‌بورد ۱۵۰ اسلایدی.

## امکانات این مرحله
- پلیر Media3 در بالای صفحه
- انتخاب ویدیو از حافظه
- پخش/توقف
- حرکت ۱۰ فریم عقب و جلو (در این نسخه با گام موقت ۳۰fps)
- نمایش زمان با انتخاب SEC / MIN
- چرخه سرعت پخش
- تنظیم روشنایی با کشیدن در نیمه چپ ناحیه راهنما و صدا در نیمه راست
- جدول ۱۵۰ فریمی با انتخاب ۱ تا ۱۵ ستون
- چهار کارگر هم‌زمان برای استخراج فریم‌ها
- سه حالت پیمایش Up / Between / Down
- اسکرول خود جدول و دستگیره کناری ضخیم و قابل کشیدن

## ساخت
- Android Studio با JDK 17
- Android Gradle Plugin 8.7.3
- compileSdk 35
- حداقل Android 7.0 (API 24)

در Android Studio پروژه را باز کنید و Task `:app:assembleDebug` را اجرا کنید.

## وضعیت
این نسخه اسکلت اولیه و نقطه شروع است؛ هنوز روی دستگاه واقعی یا GitHub Actions آزمایش نشده است.


## Latest update – multi-video import and Split

- Import Video now supports selecting multiple video files in one picker session.
- Videos opens a list of imported sources; selecting an item loads it into the preview player.
- Split is a separate Tool-row button; it is no longer an option inside Export Video.
- Split processes every imported video, with two modes:
  - Split into a requested number of parts (2–100).
  - Split into parts of a requested duration in seconds.
- Split / Save writes generated MP4 segments into the app's FVEditor folder under Movies.
- Split / Save As lets the user choose a destination folder with Android's folder picker.
- Segment names are generated from the source filename and source order.
- Export Video currently displays the ReName, tag and Original checkboxes and Save / Save As controls. The actual timeline render/export engine and the meaning/processing of these three options are not yet implemented.
- Crop, Merge, Join and other processing tools remain unimplemented.
- The existing player, storyboard layout, 150-frame storyboard and four frame extraction workers were preserved.

Build: `gradle --no-daemon :app:assembleDebug`
