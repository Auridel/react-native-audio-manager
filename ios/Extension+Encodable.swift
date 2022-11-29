//
//  Extension+Encodable.swift
//  react-native-audio-manager
//
//  Created by Oleg Efimov on 29.11.2022.
//

import Foundation

extension Encodable {
    
    func toJsonString() -> String {
        guard let jsonData = toJsonData()
        else {
            return ""
        }
        
        return String(data: jsonData, encoding: .utf8) ?? ""
    }
    
    func toJsonData() -> Data? {
        return try? JSONEncoder().encode(self)
    }
    
    func toPlainObject() -> [String: Any] {
        guard let data = toJsonData(),
              let obj = try? JSONSerialization.jsonObject(with: data, options: .fragmentsAllowed) as? [String: Any]
        else {
            return [String: Any]()
        }
        
        return obj
    }
}
