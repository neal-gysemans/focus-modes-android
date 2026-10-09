# Room generates implementation classes reflectively referenced by name.
-keep class be.nealgysemans.focusmodes.data.** { *; }

# WorkManager instantiates its InputMergers reflectively by class name, through their
# no-arg constructors. Its bundled rule keeps the classes but not the constructors, and
# R8 full mode strips them: every WorkManager job then fails with "Could not create
# Input Merger", including the job Glance renders the home-screen widget with, so the
# widget sits on its loading frame forever. Release-only — debug builds are not minified.
-keep class * extends androidx.work.InputMerger { <init>(); }

# Same trap, Glance side: a widget tap runs an ActionCallback that Glance creates by
# class name through its no-arg constructor. Glance's bundled rule keeps the class but
# not the constructor, so under R8 full mode every widget tap fails with
# NoSuchMethodException and does nothing.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
