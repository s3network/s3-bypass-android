# Release signing material. See app/keystore.properties.example.
#
# Losing the .jks is unrecoverable: Android will not update an app signed
# with a different key, so every installed copy would have to be removed by
# hand. The file is gitignored so it cannot be committed by accident — the
# backup is the part that actually matters.
app/keystore.properties
*.jks
*.keystore
