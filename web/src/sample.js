// A fictional notice for trying the app without a file, and a prepared AI summary of it.
// The AI example is static (no model call), so visitors can compare both results before deciding to sign in.
export const SAMPLE_TEXT = `지역기업 성장지원 사업 안내\n지원 대상: 지역 내 사업장을 둔 중소기업입니다.\n지원 내용: 선정 기업에 교육비 최대 500,000원을 지원합니다.\n신청 기간: 2027년 3월 1일부터 2027년 3월 20일까지입니다.\n신청 방법: 신청서와 사업자등록증을 운영팀에 제출해 주세요.\n단, 동일 항목으로 다른 지원금을 받은 기업은 제외됩니다.\n제출 서류가 누락되면 심사 대상에서 제외될 수 있습니다.\n문의: 기업지원팀, 02-0000-0000\n※ 기능 체험용 가상 문서입니다.`;
// Same shape as /api/summary items: indexes point at blocksFromText(SAMPLE_TEXT).
export const SAMPLE_AI = [
  { section: '지원 개요', statement: '지역 내 사업장을 둔 중소기업에 교육비를 최대 500,000원까지 지원하는 사업입니다.', indexes: [1, 2] },
  { section: '신청 방법과 기한', statement: '2027년 3월 1일부터 3월 20일까지 신청서와 사업자등록증을 운영팀에 제출해야 합니다.', indexes: [3, 4] },
  { section: '주의할 점', statement: '같은 항목으로 다른 지원금을 받은 기업은 제외되며, 제출 서류가 빠지면 심사에서 제외될 수 있습니다.', indexes: [5, 6] },
  { section: '문의', statement: '궁금한 점은 기업지원팀(02-0000-0000)에 문의할 수 있습니다.', indexes: [7] }
];
