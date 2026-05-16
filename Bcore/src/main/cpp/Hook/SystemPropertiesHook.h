#ifndef PHANTOM_SYSTEMPROPERTIESHOOK_H
#define PHANTOM_SYSTEMPROPERTIESHOOK_H

class SystemPropertiesHook {
public:
    // Installe les hooks Dobby sur __system_property_get,
    // __system_property_find et __system_property_read_callback.
    // Logue "SystemPropertiesHook: installed N hooks" à la fin.
    static void init();

    // Ajoute (ou remplace) une entrée dans la table des propriétés spoofées.
    // Appelé depuis Java via JNI (NativeCore.setSpoofedProperty).
    // Thread-safe. key et value sont copiés dans la table.
    static void setSpoofedProperty(const char *key, const char *value);
};

#endif
