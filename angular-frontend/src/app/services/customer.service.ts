import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class CustomerService {
  private readonly API = '/api/customers';

  constructor(private http: HttpClient) {}

  lookupOldCustomer(code: string): Observable<any> {
    return this.http.get(`${this.API}/old-lookup`, { params: { code } });
  }

  lookupCustomerByGstin(gstin: string): Observable<any> {
    return this.http.get(`${this.API}/gstin-lookup`, { params: { gstin } });
  }

  lookupCustomer(code: string): Observable<any> {
    return this.http.get(`${this.API}/lookup`, { params: { code } });
  }

  lookupAgent(code: string): Observable<any> {
    return this.http.get(`${this.API}/agent-lookup`, { params: { code } });
  }

  getMasterData(): Observable<any> {
    return this.http.get(`${this.API}/master-data`);
  }

  generateCode(companyName: string, type: string): Observable<any> {
    return this.http.post(`${this.API}/new-generate-code`, { companyName }, { params: { type } });
  }

  registerNewEntry(formData: FormData): Observable<any> {
    return this.http.post(`${this.API}/new-register`, formData);
  }

  updateOldCustomer(payload: any): Observable<any> {
    return this.http.post(`${this.API}/old-update`, payload);
  }

  ownershipLookup(code: string): Observable<any> {
    return this.http.get(`${this.API}/ownership-lookup`, { params: { code } });
  }

  ownershipSave(payload: any): Observable<any> {
    return this.http.post(`${this.API}/ownership-save`, payload);
  }

  ownershipPartyLookup(code: string): Observable<any> {
    return this.http.get(`${this.API}/ownership-party-lookup`, { params: { code } });
  }

  ownershipPartySave(payload: any): Observable<any> {
    return this.http.post(`${this.API}/ownership-party-save`, payload);
  }
}