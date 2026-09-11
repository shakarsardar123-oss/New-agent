import 'package:flutter/material.dart';

class AuraAvatar extends LeafRenderObjectWidget {
  const AuraAvatar({super.key});

  @override
  RenderObject createRenderObject(BuildContext context) {
    return RenderProxyBox();
  }
}

class RenderProxyBox extends RenderBox {}
