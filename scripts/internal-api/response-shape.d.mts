export function createResponseShapeValidator(document: unknown): (response: {
  method: string;
  path: string;
  status: number;
  contentType: string;
  payload: unknown;
}) => {
  valid: boolean;
  errors: unknown[];
  claim: 'json-response-structure-only';
  formatValidation: 'not-performed';
};
