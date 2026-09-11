import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/localization/generated/s.dart';

class ApiKeySettingsSection extends ConsumerStatefulWidget {
  const ApiKeySettingsSection({super.key});

  @override
  ConsumerState<ApiKeySettingsSection> createState() => _ApiKeySettingsSectionState();
}

class _ApiKeySettingsSectionState extends ConsumerState<ApiKeySettingsSection> {
  String _status = '';

  void _clearKey() {
    if (!mounted) return;
    setState(() {
      _status = S.of(context).aiDeleteKey;
    });
    if (!mounted) return;
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text(_status),
        ElevatedButton(
          onPressed: _clearKey,
          child: const Text('Clear Key'),
        ),
      ],
    );
  }
}
