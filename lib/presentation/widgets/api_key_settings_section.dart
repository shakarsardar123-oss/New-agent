/// Reachable settings UI for AI provider credentials and connection config.
///
/// Backed by [OpenAIProvider] for API key CRUD (secure storage) and
/// [AIConnectionStorage] for model name (SharedPreferences) and
/// base URL (secure storage). All user-visible strings use the
/// ARB-generated l10n system (S.of(context)).
///
/// Security rules enforced here:
/// - the key is never hardcoded and never printed/logged,
/// - a stored key is only ever shown masked (last 4 characters),
/// - the input field is obscured and cleared after saving,
/// - empty/whitespace-only keys are rejected,
/// - the key can be replaced or deleted,
/// - the model name is validated (non-empty, non-whitespace) before save.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/ai/api_key_masker.dart';
import '../../core/ai/ai_connection_storage.dart';
import '../../core/ai/openai_provider.dart' hide openaiProviderProvider;
import '../../l10n/app_localizations.dart';
import '../providers/app_providers.dart'
    show openaiProviderProvider, aiConnectionStorageProvider;

class ApiKeySettingsSection extends ConsumerStatefulWidget {
  const ApiKeySettingsSection({super.key});

  @override
  ConsumerState<ApiKeySettingsSection> createState() =>
      _ApiKeySettingsSectionState();
}

class _ApiKeySettingsSectionState
    extends ConsumerState<ApiKeySettingsSection> {
  final _keyController = TextEditingController();
  final _baseUrlController = TextEditingController();
  final _modelController = TextEditingController();

  bool _loading = true;
  bool _saving = false;
  String? _maskedKey;
  String? _error;
  String? _status;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _keyController.dispose();
    _baseUrlController.dispose();
    _modelController.dispose();
    super.dispose();
  }

  OpenAIProvider? get _provider {
    final provider = ref.read(openaiProviderProvider);
    return provider is OpenAIProvider ? provider : null;
  }

  AIConnectionStorage get _connectionStorage {
    return ref.read(aiConnectionStorageProvider);
  }

  /// Mask a stored key: never reveal more than the last 4 characters.
  /// Delegates to [ApiKeyMasker.mask] for testability.
  static String _mask(String key) => ApiKeyMasker.mask(key);

  Future<void> _load() async {
    final provider = _provider;
    final storage = _connectionStorage;
    if (provider == null) {
      setState(() {
        _loading = false;
        _error = 'No configurable AI provider is active.';
      });
      return;
    }
    try {
      final key = await provider.getApiKey();
      final baseUrl = await storage.getBaseUrl();
      final model = storage.getModel();
      if (!mounted) return;
      setState(() {
        _maskedKey = (key == null || key.isEmpty) ? null : _mask(key);
        _baseUrlController.text = baseUrl;
        _modelController.text = model;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'Could not read stored settings: ${e.runtimeType}';
      });
    }
  }

  Future<void> _save() async {
    final provider = _provider;
    final storage = _connectionStorage;
    if (provider == null) return;

    final key = _keyController.text.trim();
    final baseUrl = _baseUrlController.text.trim();
    final model = _modelController.text.trim();

    // Validate model before any save.
    if (model.isEmpty) {
      setState(() {
        _error = S.of(context).aiModelValidationEmpty;
        _status = null;
      });
      return;
    }

    if (key.isEmpty && _maskedKey == null) {
      setState(() {
        _error = S.of(context).aiApiKey + ' cannot be empty.';
        _status = null;
      });
      return;
    }

    setState(() {
      _saving = true;
      _error = null;
      _status = null;
    });

    try {
      if (key.isNotEmpty) {
        await provider.setApiKey(key);
      }
      if (baseUrl.isNotEmpty) {
        await storage.setBaseUrl(baseUrl);
      }
      // Save model via AIConnectionStorage (validates non-empty internally).
      storage.setModel(model);
      _keyController.clear();
      await _load();
      if (!mounted) return;
      setState(() {
        _saving = false;
        _status = S.of(context).aiSaveSecurely;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        // Report the error type only — never echo the credential.
        _error = 'Failed to save settings: ${e.runtimeType}';
      });
    }
  }

  Future<void> _delete() async {
    final provider = _provider;
    if (provider == null) return;
    setState(() {
      _saving = true;
      _error = null;
      _status = null;
    });
    try {
      await provider.deleteApiKey();
      _keyController.clear();
      await _load();
      if (!mounted) return;
      setState(() {
        _saving = false;
        _status = S.of(context).aiDeleteKey;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = 'Failed to delete key: ${e.runtimeType}';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final l10n = S.of(context);

    if (_loading) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 24),
        child: Center(child: CircularProgressIndicator()),
      );
    }

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── Connection Title ──
          Row(
            children: [
              Icon(Icons.cloud_outlined, size: 18, color: cs.primary),
              const SizedBox(width: 8),
              Text(
                l10n.aiConnectionTitle,
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),

          // ── Model Field ──
          TextField(
            controller: _modelController,
            autocorrect: false,
            decoration: InputDecoration(
              labelText: l10n.aiModel,
              hintText: l10n.aiModelHint,
              border: const OutlineInputBorder(),
              isDense: true,
            ),
          ),
          const SizedBox(height: 12),

          // ── API Key Status ──
          Row(
            children: [
              Icon(Icons.key_rounded, size: 18, color: cs.primary),
              const SizedBox(width: 8),
              Text(
                _maskedKey == null
                    ? l10n.aiNoKeyStored
                    : 'Stored key: $_maskedKey',
                style: theme.textTheme.bodyMedium,
              ),
            ],
          ),
          const SizedBox(height: 12),

          // ── API Key Input ──
          TextField(
            controller: _keyController,
            obscureText: true,
            enableSuggestions: false,
            autocorrect: false,
            decoration: InputDecoration(
              labelText: _maskedKey == null
                  ? l10n.aiApiKey
                  : l10n.aiReplaceKey,
              hintText: 'sk-…',
              border: const OutlineInputBorder(),
              isDense: true,
            ),
          ),
          const SizedBox(height: 12),

          // ── Base URL Field ──
          TextField(
            controller: _baseUrlController,
            keyboardType: TextInputType.url,
            autocorrect: false,
            decoration: InputDecoration(
              labelText: l10n.aiBaseUrl,
              border: const OutlineInputBorder(),
              isDense: true,
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: TextStyle(color: cs.error, fontSize: 12)),
          ],
          if (_status != null) ...[
            const SizedBox(height: 8),
            Text(_status!, style: TextStyle(color: cs.primary, fontSize: 12)),
          ],
          const SizedBox(height: 12),

          // ── Action Buttons ──
          Row(
            children: [
              FilledButton.icon(
                onPressed: _saving ? null : _save,
                icon: const Icon(Icons.lock_outline, size: 18),
                label: Text(l10n.aiSaveSecurely),
              ),
              const SizedBox(width: 12),
              if (_maskedKey != null)
                TextButton.icon(
                  onPressed: _saving ? null : _delete,
                  icon: const Icon(Icons.delete_outline, size: 18),
                  label: Text(l10n.aiDeleteKey),
                ),
            ],
          ),
          const SizedBox(height: 4),

          // ── Security Note ──
          Text(
            l10n.aiKeyStorageNote,
            style: TextStyle(fontSize: 11, color: cs.outline),
          ),
        ],
      ),
    );
  }
}
