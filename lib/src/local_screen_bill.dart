part of 'app.dart';

// 页面识别（无障碍）——悬浮确认框回传、确认框与落账辅助。
//
// 无障碍服务把"读到的一笔"放进待确认箱；原生侧在账单页上直接弹框。
// 用户确认后草稿带 userConfirmed=true，回到应用由 LedgerController 直接落账。

/// 一条页面识别草稿：[record] 是识别结果，[product] 是商品名，[userConfirmed]
/// 表示用户已在手机上的悬浮框里点过「确认记账」。
class ScreenBillCapture {
  const ScreenBillCapture({
    required this.record,
    required this.product,
    required this.userConfirmed,
  });

  final AutoCaptureRecord record;
  final String product;
  final bool userConfirmed;

  factory ScreenBillCapture.fromMap(Map<String, dynamic> map) => ScreenBillCapture(
    record: AutoCaptureRecord.fromJson(map),
    product: (map['product'] as String?)?.trim() ?? '',
    userConfirmed: map['userConfirmed'] == true,
  );
}

/// 确认框返回的核对结果（用户可能改过金额/店铺/商品/分类/备注）。
class ScreenBillConfirmResult {
  const ScreenBillConfirmResult({
    required this.amount,
    required this.merchant,
    required this.product,
    required this.categoryId,
    required this.note,
  });

  final double amount;
  final String merchant;
  final String product;
  final String categoryId;
  final String note;
}

/// 给出一个分类建议：优先用商户缓存 / 可信模板（快，且是用户或过去判断过的），
/// 都没有就退到默认分类。
String suggestScreenBillCategoryId(LedgerBook book, AutoCaptureRecord capture) {
  if (capture.entryType == EntryType.transfer) return 'transfer';
  final merchant = capture.merchant.trim();
  if (merchant.isNotEmpty && merchant != '未识别商户') {
    final cached = book.merchantCategories[merchant];
    if (cached != null && cached.isNotEmpty) return cached;
  }
  final template = book.captureTemplates[captureTemplateSignature(capture)];
  if (isCaptureTemplateTrusted(template)) return template!.categoryId;
  return capture.entryType == EntryType.expense
      ? resolveDefaultExpenseCategoryId(book.settings)
      : _fallbackCategoryIdForType(EntryType.income);
}

/// 把确认过的页面识别结果转成一条账本流水。
/// 金额/时间/收支来自识别（用户可改）；店铺/商品/分类/备注是识别或用户给的推测字段。
LedgerEntry screenBillToEntry(
  AutoCaptureRecord capture,
  LedgerBook book, {
  required String categoryId,
  required String merchant,
  required String product,
  required String note,
}) {
  final resolvedCategoryId = categoryId.trim().isNotEmpty
      ? categoryId.trim()
      : (capture.entryType == EntryType.transfer
            ? 'transfer'
            : resolveDefaultExpenseCategoryId(book.settings));
  final resolvedMerchant = merchant.trim();
  final resolvedProduct = product.trim();
  final resolvedNote = note.trim().isNotEmpty ? note.trim() : capture.rawBody;
  final titleParts = <String>[
    if (resolvedMerchant.isNotEmpty) resolvedMerchant,
    if (resolvedProduct.isNotEmpty) resolvedProduct,
  ];
  final title = titleParts.isNotEmpty
      ? titleParts.join(' · ')
      : '${capture.source.label}页面识别';
  final tags = <String>{
    capture.source.label,
    '页面识别',
    if (capture.entryType == EntryType.expense &&
        capture.source != CaptureSource.unknown)
      '${capture.source.label}消费',
    if (capture.entryType == EntryType.income &&
        capture.source != CaptureSource.unknown)
      '${capture.source.label}入账',
  }.toList();
  return LedgerEntry(
    id: capture.id,
    title: title,
    merchant: resolvedMerchant,
    counterpartyName: capture.counterpartyName,
    note: resolvedNote,
    amount: capture.amount,
    type: capture.entryType,
    categoryId: resolvedCategoryId,
    channel: capture.channel,
    occurredAt: DateTime.fromMillisecondsSinceEpoch(capture.postedAtMillis),
    fundingAccount: extractFundingAccount(capture),
    tags: tags,
    autoCaptured: true,
    sourceLabel: capture.source.label,
    autoMergeKey: capture.mergeKey,
    autoProfileId: capture.profileId,
    autoPostedAtMillis: capture.postedAtMillis,
    inferredFields: {
      'title',
      if (resolvedMerchant.isNotEmpty) 'merchant',
      'categoryId',
      if (note.trim().isNotEmpty) 'note',
    },
  );
}

/// 判为「同一笔」时允许的交易时间差：账单页的交易时间 vs 已有账目的发生时间。
/// 通知会比交易晚几秒到一分钟、页面时间通常精确到分，留 5 分钟足够。
const _screenBillMatchWindow = Duration(minutes: 5);

/// 在账本里找一条"就是同一笔"的账目：**金额一致 + 交易时间接近 + 商户/对象/合并键吻合**。
/// 交易时间取自账单页（[screenBillToEntry] 已把页面交易时间写进 `occurredAt`）；
/// 同商户同金额但时间差得远的，是另一次消费，不并。
int findScreenBillDuplicate(List<LedgerEntry> entries, LedgerEntry candidate) {
  return entries.lastIndexWhere((entry) {
    if (!entry.autoCaptured) return false;
    if (entry.type != candidate.type) return false;
    if ((entry.amount - candidate.amount).abs() > 0.009) return false;
    if (entry.occurredAt.difference(candidate.occurredAt).abs() >
        _screenBillMatchWindow) {
      return false;
    }

    final merchantMatch =
        _isMeaningfulText(entry.merchant) &&
        _isMeaningfulText(candidate.merchant) &&
        _sameText(entry.merchant, candidate.merchant);
    final counterpartyMatch =
        entry.counterpartyName.trim().isNotEmpty &&
        candidate.counterpartyName.trim().isNotEmpty &&
        _sameText(entry.counterpartyName, candidate.counterpartyName);
    final keyMatch =
        entry.autoMergeKey.isNotEmpty &&
        candidate.autoMergeKey.isNotEmpty &&
        entry.autoMergeKey == candidate.autoMergeKey;
    return merchantMatch || counterpartyMatch || keyMatch;
  });
}

bool _isMeaningfulText(String value) {
  final trimmed = value.trim();
  return trimmed.isNotEmpty &&
      trimmed != '未识别商户' &&
      trimmed != '未识别对象';
}

bool _sameText(String left, String right) {
  final a = left.trim();
  final b = right.trim();
  if (a.isEmpty || b.isEmpty) return false;
  return a == b || a.contains(b) || b.contains(a);
}

/// 重复时只补全已有账目**缺的**推测字段，已有的值（含用户改过的）不动。
LedgerEntry fillMissingFromScreenBill(
  LedgerEntry existing,
  LedgerEntry incoming,
) {
  final merchant = (existing.merchant.isEmpty || existing.merchant == '未识别商户')
      ? incoming.merchant
      : existing.merchant;
  return existing.copyWith(
    title: existing.title.isEmpty ? incoming.title : existing.title,
    merchant: merchant,
    counterpartyName: existing.counterpartyName.isEmpty
        ? incoming.counterpartyName
        : existing.counterpartyName,
    note: existing.note.isEmpty ? incoming.note : existing.note,
    categoryId: existing.categoryId.isEmpty
        ? incoming.categoryId
        : existing.categoryId,
    fundingAccount: existing.fundingAccount.isEmpty
        ? incoming.fundingAccount
        : existing.fundingAccount,
    tags: {...existing.tags, ...incoming.tags}.toList(),
  );
}

/// 页面识别的确认框（应用内兜底）：可改金额/店铺/商品/分类/备注，确认或忽略。
class BillConfirmDialog extends StatefulWidget {
  const BillConfirmDialog({
    super.key,
    required this.book,
    required this.draft,
    required this.product,
  });

  final LedgerBook book;
  final AutoCaptureRecord draft;
  final String product;

  @override
  State<BillConfirmDialog> createState() => _BillConfirmDialogState();
}

class _BillConfirmDialogState extends State<BillConfirmDialog> {
  late final TextEditingController _amountController;
  late final TextEditingController _merchantController;
  late final TextEditingController _productController;
  late final TextEditingController _noteController;
  late List<AppCategory> _categories;
  late String _categoryId;

  @override
  void initState() {
    super.initState();
    _amountController = TextEditingController(
      text: widget.draft.amount.toStringAsFixed(2),
    );
    _merchantController = TextEditingController(
      text: widget.draft.merchant == '未识别商户' ? '' : widget.draft.merchant,
    );
    _productController = TextEditingController(text: widget.product);
    _noteController = TextEditingController();
    _categories = categoriesForType(widget.draft.entryType);
    if (_categories.isEmpty) _categories = appCategories;
    _categoryId = suggestScreenBillCategoryId(widget.book, widget.draft);
    if (_categories.every((category) => category.id != _categoryId)) {
      _categoryId = _categories.first.id;
    }
  }

  @override
  void dispose() {
    _amountController.dispose();
    _merchantController.dispose();
    _productController.dispose();
    _noteController.dispose();
    super.dispose();
  }

  String get _draftTimeLabel {
    final at = DateTime.fromMillisecondsSinceEpoch(widget.draft.postedAtMillis);
    return DateFormat('yyyy-MM-dd HH:mm').format(at);
  }

  void _confirm() {
    final amount =
        double.tryParse(_amountController.text.trim().replaceAll(',', '')) ??
        widget.draft.amount;
    Navigator.of(context).pop(
      ScreenBillConfirmResult(
        amount: amount > 0 ? amount : widget.draft.amount,
        merchant: _merchantController.text.trim(),
        product: _productController.text.trim(),
        categoryId: _categoryId,
        note: _noteController.text.trim(),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final draft = widget.draft;
    final typeLabel = switch (draft.entryType) {
      EntryType.expense => '支出',
      EntryType.income => '收入',
      EntryType.transfer => '转账',
    };
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
      title: const Text('页面识别，核对一下'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '来源：${draft.source.label} · $typeLabel',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 4),
            Text(
              '交易时间：$_draftTimeLabel',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _amountController,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              decoration: const InputDecoration(labelText: '金额'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _merchantController,
              decoration: const InputDecoration(labelText: '店铺 / 对方（可留空）'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _productController,
              decoration: const InputDecoration(labelText: '商品（可留空）'),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              value: _categoryId,
              decoration: const InputDecoration(labelText: '分类'),
              items: [
                for (final category in _categories)
                  DropdownMenuItem(
                    value: category.id,
                    child: Text(category.name),
                  ),
              ],
              onChanged: (value) {
                if (value != null) setState(() => _categoryId = value);
              },
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _noteController,
              decoration: const InputDecoration(labelText: '备注（可留空）'),
            ),
            const SizedBox(height: 12),
            Text(
              '页面原文：${draft.rawBody}',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: const Color(0xFF73809A),
              ),
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('忽略'),
        ),
        FilledButton(onPressed: _confirm, child: const Text('确认记账')),
      ],
    );
  }
}
