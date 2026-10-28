# Public development signing key

voicedub-debug.keystore is deliberately public and used only for debug APKs. Password and alias use Android's usual public debug defaults. Keeping this file stable permits updates between builds from 0.5 onward. Never use it for a production release or privileged application. Older 0.4 CI APKs used ephemeral runner-generated keys and cannot necessarily be updated in place; Android may require a one-time uninstall, which removes downloaded app models.
