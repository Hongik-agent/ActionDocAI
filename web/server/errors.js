// Only fixed public codes cross the API boundary; never return raw exception text.
export function summaryFailure(error) {
  const code=error?.message;
  if(['provider_busy','provider_limit','provider_timeout','provider_connection','provider_configuration'].includes(code))return code;
  if(['audit','numbers','evidence','items'].includes(code))return 'verification';
  if(code==='incomplete' || error instanceof SyntaxError)return 'response_incomplete';
  if(['quota','cache'].includes(code))return 'storage';
  if(code==='configuration')return 'provider_configuration';
  return 'unavailable';
}
