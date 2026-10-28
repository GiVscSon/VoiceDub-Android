# Public development signing key

voicedub-debug.keystore is deliberately public and used only for debug APKs. Password and alias use Android's usual public debug defaults. Keeping this file stable permits updates between preview builds from 0.5 onward. Never use it for a production release or privileged application. Older 0.4 CI APKs used ephemeral runner-generated keys. Version 0.5 uses applicationId com.voicedub.app.preview and app-scoped subtitle permissions, so it installs alongside 0.4 without removing downloaded models from the working app.
